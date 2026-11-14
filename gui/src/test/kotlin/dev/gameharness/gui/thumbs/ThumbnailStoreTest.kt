package dev.gameharness.gui.thumbs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The thumbnail caches are what keep folder navigation off the disk and off
 * the decoder. They fail quietly in both directions: a cache that never hits
 * brings the lag back, and one that hits when it should not shows an old
 * picture for a file that has changed.
 */
class ThumbnailStoreTest {

    private fun image(file: File, width: Int, height: Int, format: EncodedImageFormat, color: Int = 0xFF2288CC.toInt()) {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(color)
        file.writeBytes(surface.makeImageSnapshot().encodeToData(format, 90)!!.bytes)
        surface.close()
    }

    /** A store whose MODEL thumbnails are made by [produce], counting how often it is asked. */
    private fun countingStore(cache: Path?, calls: AtomicInteger, produce: (File, Int) -> Image?): ThumbnailStore {
        val store = ThumbnailStore(cache)
        store.register(ThumbnailKind.MODEL, Dispatchers.Default) { file, edge ->
            calls.incrementAndGet()
            produce(file, edge)
        }
        return store
    }

    @Test
    fun `a large image is scaled to the thumbnail edge and a small one is left alone`(@TempDir tmp: Path) {
        val png = tmp.resolve("wide.png").toFile().also { image(it, 1000, 500, EncodedImageFormat.PNG) }
        val jpeg = tmp.resolve("photo.jpg").toFile().also { image(it, 2048, 1024, EncodedImageFormat.JPEG) }
        val pixelArt = tmp.resolve("coin.png").toFile().also { image(it, 16, 16, EncodedImageFormat.PNG) }

        ThumbnailStore.decodeScaled(png, 320)!!.use { assertEquals(320 to 160, it.width to it.height) }
        // the JPEG path decodes at an eighth or a quarter first; the result must still be exact
        ThumbnailStore.decodeScaled(jpeg, 320)!!.use { assertEquals(320 to 160, it.width to it.height) }
        ThumbnailStore.decodeScaled(pixelArt, 320)!!.use { assertEquals(16 to 16, it.width to it.height) }
    }

    @Test
    fun `a second request is served from memory`(@TempDir tmp: Path) = runBlocking {
        val file = tmp.resolve("a.png").toFile().also { image(it, 64, 64, EncodedImageFormat.PNG) }
        val calls = AtomicInteger()
        val store = countingStore(null, calls) { f, edge -> ThumbnailStore.decodeScaled(f, edge) }

        assertNull(store.cached(file.path), "nothing before the first load")
        val first = assertNotNull(store.load(file.path, ThumbnailKind.MODEL))
        val second = store.load(file.path, ThumbnailKind.MODEL)

        assertSame(first, second)
        assertSame(first, store.cached(file.path), "and the UI thread can have it without waiting")
        assertEquals(1, calls.get())
    }

    @Test
    fun `a new run is served from the disk cache without making the thumbnail again`(@TempDir tmp: Path) = runBlocking {
        val cache = tmp.resolve("cache")
        val file = tmp.resolve("a.png").toFile().also { image(it, 640, 640, EncodedImageFormat.PNG) }
        val calls = AtomicInteger()

        val firstRun = countingStore(cache, calls) { f, edge -> ThumbnailStore.decodeScaled(f, edge) }
        assertNotNull(firstRun.load(file.path, ThumbnailKind.MODEL))
        assertEquals(1, calls.get())
        assertEquals(1, Files.list(cache).use { it.count() }.toInt(), "one cache file written")

        val secondRun = countingStore(cache, calls) { f, edge -> ThumbnailStore.decodeScaled(f, edge) }
        val fromDisk = assertNotNull(secondRun.load(file.path, ThumbnailKind.MODEL))
        assertEquals(1, calls.get(), "the second run must not produce it again")
        assertEquals(320, fromDisk.width)
    }

    @Test
    fun `a file that changed in place gets a new thumbnail`(@TempDir tmp: Path) = runBlocking {
        val cache = tmp.resolve("cache")
        val file = tmp.resolve("a.png").toFile().also { image(it, 64, 64, EncodedImageFormat.PNG) }
        val calls = AtomicInteger()
        val store = countingStore(cache, calls) { f, edge -> ThumbnailStore.decodeScaled(f, edge) }
        val before = assertNotNull(store.load(file.path, ThumbnailKind.MODEL))

        // what trimming a sprite does: same path, new contents
        image(file, 32, 48, EncodedImageFormat.PNG, color = 0xFFCC2222.toInt())
        file.setLastModified(file.lastModified() + 5_000)
        val after = assertNotNull(store.load(file.path, ThumbnailKind.MODEL))

        assertNotSame(before, after)
        assertEquals(32 to 48, after.width to after.height)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a file no thumbnail can be made of is not tried again until it changes`(@TempDir tmp: Path) = runBlocking {
        val file = tmp.resolve("broken.glb").toFile().also { it.writeText("not a model") }
        val calls = AtomicInteger()
        val store = countingStore(null, calls) { _, _ -> throw IllegalStateException("cannot draw this") }

        assertNull(store.load(file.path, ThumbnailKind.MODEL))
        assertNull(store.load(file.path, ThumbnailKind.MODEL))
        assertEquals(1, calls.get(), "a 60 MB model that cannot be read must not be read on every scroll")

        file.writeText("still not a model, but different")
        file.setLastModified(file.lastModified() + 5_000)
        assertNull(store.load(file.path, ThumbnailKind.MODEL))
        assertEquals(2, calls.get())
    }

    @Test
    fun `a missing file has no thumbnail and is not an error`(@TempDir tmp: Path) = runBlocking {
        val store = ThumbnailStore(null)
        assertNull(store.load(tmp.resolve("gone.png").toString(), ThumbnailKind.IMAGE))
        assertEquals(0, store.pending)
    }

    @Test
    fun `the disk cache is cut back, oldest first, once it passes its limit`(@TempDir tmp: Path) {
        val cache = Files.createDirectories(tmp.resolve("cache"))
        val now = System.currentTimeMillis()
        for (i in 0 until 10) {
            val f = cache.resolve("t$i.webp").toFile()
            f.writeBytes(ByteArray(1000))
            f.setLastModified(now - (10 - i) * 60_000L) // t0 oldest
        }
        val store = ThumbnailStore(cache)

        store.pruneDisk(limitBytes = 20_000)
        assertEquals(10, cache.toFile().list()!!.size, "under the limit nothing goes")

        store.pruneDisk(limitBytes = 8_000)
        val left = cache.toFile().list()!!.sorted()
        assertEquals(6, left.size, "down to three quarters of the limit")
        assertTrue("t9.webp" in left && "t0.webp" !in left, "the newest stay: $left")
    }
}
