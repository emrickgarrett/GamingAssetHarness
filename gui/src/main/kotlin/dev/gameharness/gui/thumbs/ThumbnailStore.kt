package dev.gameharness.gui.thumbs

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private val logger = LoggerFactory.getLogger("ThumbnailStore")

/** What a thumbnail is made from; each kind has its own way of producing the picture. */
enum class ThumbnailKind { IMAGE, MODEL }

/**
 * Small pictures of asset files for the browser grid, made off the UI thread
 * and kept in two caches.
 *
 * WHY THIS EXISTS: every card used to decode its whole source image on the UI
 * thread while it was being composed, and threw the result away when it left
 * the screen. A folder of 170 one-megapixel JPEGs froze the window for about two
 * seconds each time it was opened, and again on every row scrolled into view.
 *
 * - In memory: finished thumbnails by file path, least recently used out first,
 *   capped by bytes. A folder seen before draws complete in its first frame.
 * - On disk ([cacheDir]): WebP files named by a hash of path, modification time,
 *   size and edge length, so a changed file can never match an old thumbnail.
 *   A folder seen in an earlier run costs one small decode per card.
 *
 * [cached] is the only call meant for the UI thread. [load] does the file work
 * on its own dispatchers and also notices a file that changed under a cached
 * entry (a trimmed or regenerated sprite keeps its path).
 */
class ThumbnailStore(
    private val cacheDir: Path?,
    val maxEdge: Int = 320,
    private val memoryBudgetBytes: Long = 192L * 1024 * 1024,
) {
    /** Makes the picture for one kind of file: (file, longest edge) to an image, or null if it cannot. */
    fun interface Producer {
        fun produce(file: File, maxEdge: Int): Image?
    }

    private class Entry(val bitmap: ImageBitmap, val modified: Long, val length: Long, val bytes: Long)

    private val memory = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {}
    private var memoryBytes = 0L
    /** Files no thumbnail could be made of, with the modification time and size they had then. */
    private val failed = HashMap<String, Pair<Long, Long>>()
    private val inFlight = AtomicInteger(0)

    private val producers = HashMap<ThumbnailKind, Producer>()
    private val dispatchers = HashMap<ThumbnailKind, CoroutineDispatcher>()

    init {
        val workers = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        register(ThumbnailKind.IMAGE, Dispatchers.IO.limitedParallelism(workers)) { file, edge ->
            decodeScaled(file, edge)
        }
        if (cacheDir != null) {
            runCatching { Files.createDirectories(cacheDir) }
                .onFailure { logger.warn("Thumbnail cache directory unavailable: {}", it.message) }
        }
    }

    /** Installs the way thumbnails of [kind] are made, and the dispatcher that work runs on. */
    fun register(kind: ThumbnailKind, dispatcher: CoroutineDispatcher, producer: Producer) {
        synchronized(producers) {
            producers[kind] = producer
            dispatchers[kind] = dispatcher
        }
    }

    fun supports(kind: ThumbnailKind): Boolean = synchronized(producers) { kind in producers }

    /** Thumbnails being made right now; the benchmark waits on this. */
    val pending: Int get() = inFlight.get()

    /** The thumbnail for [path] if it is already in memory. Never touches the disk. */
    fun cached(path: String): ImageBitmap? = synchronized(memory) { memory[path]?.bitmap }

    /**
     * The thumbnail for [path], from memory, the disk cache, or freshly made.
     * Null when the file is missing, unreadable, or nothing can draw its kind.
     */
    suspend fun load(path: String, kind: ThumbnailKind): ImageBitmap? {
        val (producer, dispatcher) = synchronized(producers) {
            (producers[kind] ?: return null) to (dispatchers[kind] ?: return null)
        }
        inFlight.incrementAndGet()
        try {
            // the stat and the disk cache are cheap and must not queue behind slow producers
            val quick = withContext(Dispatchers.IO) { fromCaches(path) }
            when (quick) {
                is Lookup.Hit -> return quick.bitmap
                is Lookup.Gone -> return null
                is Lookup.Miss -> {
                    return withContext(dispatcher) {
                        val image = try {
                            producer.produce(quick.file, maxEdge)
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            logger.debug("No thumbnail for {}: {}", quick.file.name, e.toString())
                            null
                        }
                        if (image == null) {
                            // remembered, or a file that cannot be drawn would be read again
                            // in full every time its card scrolled into view
                            synchronized(memory) { failed[path] = quick.modified to quick.length }
                            return@withContext null
                        }
                        image.use {
                            writeDisk(quick.diskFile, it)
                            remember(path, it.toComposeImageBitmap(), quick.modified, quick.length)
                        }
                    }
                }
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private sealed interface Lookup {
        class Hit(val bitmap: ImageBitmap) : Lookup
        data object Gone : Lookup
        class Miss(val file: File, val modified: Long, val length: Long, val diskFile: Path?) : Lookup
    }

    private fun fromCaches(path: String): Lookup {
        val file = File(path)
        val length = file.length()
        if (length == 0L) {
            synchronized(memory) { memory.remove(path)?.let { memoryBytes -= it.bytes } }
            return Lookup.Gone
        }
        val modified = file.lastModified()

        synchronized(memory) {
            val entry = memory[path]
            if (entry != null && entry.modified == modified && entry.length == length) return Lookup.Hit(entry.bitmap)
            if (failed[path] == (modified to length)) return Lookup.Gone
        }

        val diskFile = cacheDir?.resolve(diskName(path, modified, length))
        if (diskFile != null && Files.isRegularFile(diskFile)) {
            try {
                val bitmap = Image.makeFromEncoded(Files.readAllBytes(diskFile)).use { it.toComposeImageBitmap() }
                return Lookup.Hit(remember(path, bitmap, modified, length))
            } catch (e: Exception) {
                // a torn or foreign file: make it again
                runCatching { Files.deleteIfExists(diskFile) }
            }
        }
        return Lookup.Miss(file, modified, length, diskFile)
    }

    private fun remember(path: String, bitmap: ImageBitmap, modified: Long, length: Long): ImageBitmap {
        val bytes = bitmap.width.toLong() * bitmap.height * 4
        synchronized(memory) {
            memory.put(path, Entry(bitmap, modified, length, bytes))?.let { memoryBytes -= it.bytes }
            memoryBytes += bytes
            val it = memory.entries.iterator()
            while (memoryBytes > memoryBudgetBytes && it.hasNext()) {
                val eldest = it.next()
                if (eldest.key == path) continue
                memoryBytes -= eldest.value.bytes
                it.remove()
            }
        }
        return bitmap
    }

    private fun diskName(path: String, modified: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$FORMAT_VERSION|$path|$modified|$length|$maxEdge".toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".webp"
    }

    private fun writeDisk(target: Path?, image: Image) {
        if (target == null) return
        try {
            val data = image.encodeToData(EncodedImageFormat.WEBP, 90) ?: return
            val tmp = target.resolveSibling(target.fileName.toString() + "." + Thread.currentThread().id + ".tmp")
            Files.write(tmp, data.bytes)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.debug("Could not write thumbnail cache file: {}", e.toString())
        }
    }

    /**
     * Deletes the oldest cache files once the directory passes [limitBytes],
     * down to three quarters of it. Thumbnails of files that changed or were
     * deleted are never asked for again, so without this they would pile up.
     */
    fun pruneDisk(limitBytes: Long = 512L * 1024 * 1024) {
        val dir = cacheDir ?: return
        try {
            val files = dir.toFile().listFiles { f -> f.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= limitBytes) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= limitBytes * 3 / 4) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (e: Exception) {
            logger.debug("Thumbnail cache prune skipped: {}", e.toString())
        }
    }

    companion object {
        /** Bump to orphan every cached file when the way thumbnails are drawn changes. */
        private const val FORMAT_VERSION = 1

        /**
         * Decodes [file] to at most [maxEdge] on its longest side, never larger
         * than the source (small pixel art is left at its own size).
         *
         * JPEGs are asked for at a half, quarter or eighth where that still
         * covers the target: the decoder then skips most of the work, which is
         * the difference between about 4 ms and 25 ms for a one-megapixel photo.
         */
        fun decodeScaled(file: File, maxEdge: Int): Image? {
            val data = Data.makeFromBytes(file.readBytes())
            val codec = Codec.makeFromData(data)
            try {
                val srcW = codec.width
                val srcH = codec.height
                if (srcW <= 0 || srcH <= 0) return null
                val scale = minOf(1f, maxEdge.toFloat() / max(srcW, srcH))
                val outW = max(1, (srcW * scale).roundToInt())
                val outH = max(1, (srcH * scale).roundToInt())

                val decoded = decodeNear(codec, outW, outH)
                decoded.use { bitmap ->
                    bitmap.setImmutable()
                    val source = Image.makeFromBitmap(bitmap)
                    if (source.width == outW && source.height == outH) return source
                    source.use {
                        val surface = Surface.makeRasterN32Premul(outW, outH)
                        surface.use { s ->
                            s.canvas.drawImageRect(
                                it,
                                Rect.makeWH(it.width.toFloat(), it.height.toFloat()),
                                Rect.makeWH(outW.toFloat(), outH.toFloat()),
                                FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR),
                                null,
                                true,
                            )
                            return s.makeImageSnapshot()
                        }
                    }
                }
            } finally {
                codec.close()
                data.close()
            }
        }

        /** The smallest decode the codec offers that still covers [outW] x [outH]. */
        private fun decodeNear(codec: Codec, outW: Int, outH: Int): Bitmap {
            val srcW = codec.width
            val srcH = codec.height
            if (codec.encodedImageFormat == EncodedImageFormat.JPEG) {
                for (eighths in intArrayOf(1, 2, 4)) {
                    val w = ceil(srcW * eighths / 8.0).toInt()
                    val h = ceil(srcH * eighths / 8.0).toInt()
                    if (w < outW || h < outH) continue
                    val bitmap = Bitmap()
                    try {
                        bitmap.allocPixels(ImageInfo(w, h, ColorType.N32, ColorAlphaType.PREMUL))
                        codec.readPixels(bitmap, 0)
                        return bitmap
                    } catch (e: Exception) {
                        bitmap.close() // this size is not on offer after all; try the next
                    }
                }
            }
            val bitmap = Bitmap()
            bitmap.allocPixels(ImageInfo(srcW, srcH, ColorType.N32, ColorAlphaType.PREMUL))
            codec.readPixels(bitmap, 0)
            return bitmap
        }
    }
}
