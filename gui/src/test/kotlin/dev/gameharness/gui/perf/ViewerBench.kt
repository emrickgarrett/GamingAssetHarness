package dev.gameharness.gui.perf

import dev.gameharness.gui.viewer3d.PreparedModel
import dev.gameharness.gui.viewer3d.ViewerSession
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Times the 3D viewer on real model files, with no window: how long a model
 * takes to open (read, decode textures, first picture), and how fast pictures
 * come while it is being turned and while its animation plays.
 *
 *   ./gradlew :gui:viewerBench --args="<width> <height> <model> [<model> ...]"
 */
object ViewerBench {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            System.err.println("usage: <width> <height> <model> [<model> ...]")
            kotlin.system.exitProcess(2)
        }
        val width = args[0].toInt()
        val height = args[1].toInt()

        println("%-44s %7s | %8s %8s | %9s %9s | %9s".format(
            "model", "MB", "load ms", "first ms", "orbit fps", "worst ms", "anim fps"))
        for (path in args.drop(2)) {
            val file = File(path)
            val frames = AtomicInteger()
            val lastFrame = AtomicLong()
            val worstGap = AtomicLong()
            val session = ViewerSession(
                onFrame = { frame ->
                    frame.image.close()
                    val now = System.nanoTime()
                    val previous = lastFrame.getAndSet(now)
                    if (previous != 0L) worstGap.accumulateAndGet(now - previous) { a, b -> maxOf(a, b) }
                    frames.incrementAndGet()
                },
                onUnavailable = { System.err.println("3D view unavailable: $it"); kotlin.system.exitProcess(1) },
            )
            session.resize(width, height)

            val t0 = System.nanoTime()
            val prepared = PreparedModel.load(file.toPath())
            val loadMs = (System.nanoTime() - t0) / 1e6
            val animated = prepared.defaultAnimation >= 0
            session.setModel(prepared)
            session.setPlaying(false)
            while (frames.get() == 0) Thread.sleep(1)
            val firstMs = (System.nanoTime() - t0) / 1e6

            // turn it for two seconds, asking for a new view far more often than one can be drawn
            Thread.sleep(200)
            frames.set(0); lastFrame.set(0); worstGap.set(0)
            val orbitStart = System.nanoTime()
            while (System.nanoTime() - orbitStart < 2_000_000_000L) {
                session.orbit(3f, 1f)
                Thread.sleep(2)
            }
            val orbitFps = frames.get() / 2.0
            val orbitWorst = worstGap.get() / 1e6

            var animFps = 0.0
            if (animated) {
                session.setPlaying(true)
                Thread.sleep(200)
                frames.set(0)
                Thread.sleep(2000)
                animFps = frames.get() / 2.0
            }
            session.close()

            println("%-44s %7.1f | %8.0f %8.0f | %9.1f %9.1f | %9s".format(
                file.name.take(44), file.length() / 1048576.0, loadMs, firstMs, orbitFps, orbitWorst,
                if (animated) "%.1f".format(animFps) else "-"))
        }
        kotlin.system.exitProcess(0)
    }
}
