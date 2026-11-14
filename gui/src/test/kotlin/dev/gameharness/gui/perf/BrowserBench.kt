package dev.gameharness.gui.perf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.gameharness.core.model.Workspace
import dev.gameharness.core.workspace.WorkspaceManager
import dev.gameharness.gui.components.AssetPreview
import dev.gameharness.gui.theme.GameHarnessTheme
import dev.gameharness.gui.thumbs.Thumbnails
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32

/**
 * Times the asset browser against a real workspace, with no window on screen.
 *
 * The browser is composed into an [ImageComposeScene], which renders on the
 * calling thread: the time one `render()` takes is the time the UI thread would
 * be busy, which is what "laggy" means to the person clicking. For every folder
 * it reports the first frame after opening it ("open"), the slowest frame until
 * the picture stops changing ("worst"), how long that takes ("all in": the last
 * thumbnail arriving), the frames of a scroll to the bottom, and the frame of
 * going back to the root.
 *
 * The workspace file is only read. Nothing here calls the workspace manager, so
 * a real project's `workspace.json` is safe to point it at.
 *
 *   ./gradlew :gui:browserBench --args="<workspace.json> <out-dir> [width] [height] [folder ...] [--cache=<dir>]"
 */
object BrowserBench {

    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(rawArgs: Array<String>) {
        // --cache=<dir> puts the thumbnail disk cache there; without it thumbnails stay in memory
        val cache = rawArgs.firstOrNull { it.startsWith("--cache=") }?.substringAfter("=")
        System.setProperty("gameharness.thumbnailCache", cache ?: "off")
        val args = rawArgs.filterNot { it.startsWith("--") }
        if (args.size < 2) {
            System.err.println("usage: <workspace.json> <out-dir> [width] [height] [folder ...] [--cache=<dir>]")
            kotlin.system.exitProcess(2)
        }
        val workspace = json.decodeFromString(Workspace.serializer(), File(args[0]).readText())
        val outDir = File(args[1]).apply { mkdirs() }
        val width = args.getOrNull(2)?.toIntOrNull() ?: 1400
        val height = args.getOrNull(3)?.toIntOrNull() ?: 1300
        val only = args.drop(4).toSet()

        val counts = workspace.assets.groupingBy { it.folder }.eachCount()
        val folders = workspace.folders.sortedByDescending { counts[it] ?: 0 }
            .filter { only.isEmpty() || it in only }

        var currentFolder by mutableStateOf("")
        val scene = ImageComposeScene(width, height, Density(1f)) {
            GameHarnessTheme(darkTheme = true) {
                AssetPreview(
                    preview = null,
                    assets = workspace.assets,
                    folders = workspace.folders,
                    currentFolder = currentFolder,
                    onNavigateToFolder = { currentFolder = it },
                    onNavigateUp = { currentFolder = currentFolder.substringBeforeLast("/", "") },
                )
            }
        }

        fun frame(): Pair<Double, Image> {
            val t0 = System.nanoTime()
            val image = scene.render(System.nanoTime())
            return (System.nanoTime() - t0) / 1e6 to image
        }

        fun crc(image: Image): Long {
            val bitmap = Bitmap.makeFromImage(image)
            val bytes = bitmap.readPixels() ?: return 0
            bitmap.close()
            return CRC32().apply { update(bytes) }.value
        }

        /** Renders until the picture has stopped changing; returns (ms until then, frames, worst frame ms). */
        fun settle(limitMs: Long = 60_000): Triple<Double, Int, Double> {
            val start = System.nanoTime()
            var lastChange = start
            var last = -1L
            var same = 0
            var frames = 0
            var worst = 0.0
            while ((same < 12 || Thumbnails.store.pending > 0) && (System.nanoTime() - start) / 1_000_000 < limitMs) {
                val (ms, image) = frame()
                frames++
                if (ms > worst) worst = ms
                val sum = crc(image)
                if (sum == last) same++ else { same = 0; last = sum; lastChange = System.nanoTime() }
                Thread.sleep(8)
            }
            return Triple((lastChange - start) / 1e6, frames, worst)
        }

        fun save(image: Image, name: String) {
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: return
            File(outDir, name).writeBytes(data.bytes)
        }

        fun usedMb(): Long {
            repeat(3) { System.gc(); Thread.sleep(40) }
            val rt = Runtime.getRuntime()
            return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        }

        // the root, so the scene is warm (fonts, shaders of the software rasteriser) before timing
        repeat(5) { frame() }
        settle()
        println("workspace: ${workspace.name}, ${workspace.assets.size} assets, ${workspace.folders.size} folders, view ${width}x$height")
        println("heap at root: ${usedMb()} MB")

        // The look for files changed from outside, which the app repeats every five seconds.
        val registry = Files.createTempDirectory("browser-bench-registry")
        val manager = WorkspaceManager(registry)
        val checks = (1..7).map {
            val t0 = System.nanoTime()
            manager.hasExternalChanges(workspace)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        registry.toFile().deleteRecursively()
        println("check for outside changes: median %.1f ms, worst %.1f ms".format(checks[checks.size / 2], checks.last()))
        println()
        // Twice round: the first visit of each folder in this process, then a
        // second visit, which is what moving back and forth between folders costs.
        for (pass in 1..2) {
            println(if (pass == 1) "first visit" else "second visit")
            println("%-12s %6s | %9s %9s %9s | %8s %8s %8s %7s | %8s".format(
                "folder", "items", "open ms", "worst ms", "all in ms", "scr avg", "scr p95", "scr max", "frames", "back ms"))
            for (folder in folders) {
                val items = counts[folder] ?: 0

                currentFolder = folder
                val (openMs, firstImage) = frame()
                val (settleMs, _, settleWorst) = settle()
                if (pass == 1) save(scene.render(System.nanoTime()), "open_${folder.replace('/', '_')}.png")

                // Scroll to the far end, one wheel notch a frame. The grid remembers
                // where each folder was left, so the second visit starts at the
                // bottom and scrolls back up.
                val direction = if (pass == 1) 1f else -1f
                val scrollTimes = ArrayList<Double>()
                var last = crc(firstImage)
                var unchanged = 0
                var steps = 0
                while (unchanged < 6 && steps < 1500) {
                    scene.sendPointerEvent(
                        eventType = PointerEventType.Scroll,
                        position = Offset(width / 2f, height / 2f),
                        scrollDelta = Offset(0f, direction),
                    )
                    val (ms, image) = frame()
                    scrollTimes.add(ms)
                    steps++
                    val sum = crc(image)
                    if (sum == last) unchanged++ else { unchanged = 0; last = sum }
                }
                settle()

                currentFolder = ""
                val (backMs, _) = frame()
                settle()

                val sorted = scrollTimes.sorted()
                val avg = if (sorted.isEmpty()) 0.0 else sorted.average()
                val p95 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)]
                val max = sorted.lastOrNull() ?: 0.0
                println("%-12s %6d | %9.1f %9.1f %9.1f | %8.1f %8.1f %8.1f %7d | %8.1f".format(
                    folder, items, openMs, maxOf(openMs, settleWorst), settleMs, avg, p95, max, scrollTimes.size, backMs))
            }
            println()
        }
        scene.close()
        kotlin.system.exitProcess(0)
    }
}
