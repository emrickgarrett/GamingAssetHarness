package dev.gameharness.cli.commands.sculpt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `harness sculpt render` — screenshot a baked .glb from one or more angles.
 *
 * This is the missing half of the img2threejs loop. Every pass is gated on a
 * reference-vs-render comparison, and the forge deliberately ships no renderer,
 * so without this the agent is judging its own geometry blind. The shots come
 * from the harness's own viewer ([dev.gameharness.gui.viewer3d.OffscreenRenderer]),
 * which means a review render and the asset window show the same thing.
 *
 * The renderer lives in :gui and runs in a spawned JVM off a classpath file the
 * gui build publishes — see the `renderClasspath` task for why it is not an
 * ordinary module dependency.
 */
class SculptRender : CliktCommand(name = "render", help = "Render a baked .glb to PNG(s) for review") {

    private val model by option("-m", "--model", help = "Path to the .glb to render").required()
    private val out by option("-o", "--out", help = "Output PNG, or a directory when --angles has several").required()
    private val size by option("--size", help = "Square output size in px").int().default(720)
    private val angles by option(
        "--angles",
        help = "Comma-separated azimuth:elevation pairs, e.g. '35:20,0:0,90:15'. Default: three-quarter, front, side, top.",
    ).default("35:20,0:5,90:15,20:70")

    override fun run() {
        val glb = File(model).absoluteFile
        if (!glb.isFile) {
            printError("sculpt.render", "NOT_FOUND", "Model not found: ${glb.path}")
            return
        }

        val classpath = findRepoDir("gui/build") { File(it, "renderer-classpath.txt").isFile }
            ?.let { File(it, "renderer-classpath.txt") }
        if (classpath == null) {
            printError(
                "sculpt.render",
                "RENDERER_MISSING",
                "renderer classpath not published — run './gradlew :gui:renderClasspath' once " +
                    "(looked above ${searchRoots().joinToString(" and ") { it.path }})",
            )
            return
        }

        val shots = parseAngles(angles)
        if (shots.isEmpty()) {
            printError("sculpt.render", "BAD_ANGLES", "could not parse --angles '$angles'")
            return
        }

        // One angle writes exactly the file asked for; several need distinct names,
        // so --out is treated as a directory and the angle names the file.
        val outFile = File(out).absoluteFile
        val multi = shots.size > 1
        if (multi) outFile.mkdirs()

        val cp = classpath.readText().trim()
        val results = mutableListOf<JsonObject>()
        for ((azimuth, elevation) in shots) {
            val target = if (multi) {
                File(outFile, "${glb.nameWithoutExtension}-az${fmt(azimuth)}-el${fmt(elevation)}.png")
            } else {
                outFile
            }
            val report = renderOne(cp, glb, target, azimuth, elevation) ?: return
            results += report
        }

        printSuccess(
            "sculpt.render",
            JsonObject(
                mapOf(
                    "model" to JsonPrimitive(glb.path),
                    "count" to JsonPrimitive(results.size),
                    "triangles" to (results.first()["triangles"] ?: JsonPrimitive(0)),
                    "renders" to JsonArray(results),
                )
            )
        )
    }

    /** Runs the offscreen renderer once; prints the error and returns null on failure. */
    private fun renderOne(cp: String, glb: File, target: File, azimuth: Float, elevation: Float): JsonObject? {
        // The renderer's classpath (Compose Desktop and friends) runs to tens of
        // kilobytes, past the Windows CreateProcess limit, so -cp goes in a JDK
        // @argfile instead of on the command line.
        val argFile = File.createTempFile("sculpt-render-cp", ".txt")
        argFile.writeText("-cp\n\"${cp.replace('\\', '/')}\"\n")

        val cmd = mutableListOf(javaBin(), "@${argFile.path}")
        // macOS parks GLFW on the process's first thread
        if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) cmd += "-XstartOnFirstThread"
        cmd += listOf(
            "dev.gameharness.gui.viewer3d.OffscreenRenderMain",
            glb.path, target.path, size.toString(), size.toString(),
            azimuth.toString(), elevation.toString(),
        )

        val proc = ProcessBuilder(cmd).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val finished = proc.waitFor(RENDER_TIMEOUT_SEC, TimeUnit.SECONDS)
        argFile.delete()
        if (!finished) {
            proc.destroyForcibly()
            printError("sculpt.render", "TIMEOUT", "render timed out after ${RENDER_TIMEOUT_SEC}s")
            return null
        }
        if (proc.exitValue() != 0) {
            printError("sculpt.render", "RENDER_FAILED", (stderr.ifBlank { stdout }).trim().takeLast(1500))
            return null
        }

        val report = runCatching {
            Json.parseToJsonElement(stdout.lines().last { it.trim().startsWith("{") }) as JsonObject
        }.getOrElse {
            printError("sculpt.render", "BAD_OUTPUT", "could not parse render output: ${stdout.trim().takeLast(500)}")
            return null
        }
        return JsonObject(
            mapOf(
                "path" to JsonPrimitive(target.path),
                "azimuth" to JsonPrimitive(azimuth),
                "elevation" to JsonPrimitive(elevation),
                "triangles" to (report["triangles"] ?: JsonPrimitive(0)),
            )
        )
    }

    private companion object {
        const val RENDER_TIMEOUT_SEC = 120L

        /** Whole degrees where possible, so filenames stay readable. */
        fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString().replace('.', '_')

        fun javaBin(): String {
            val home = System.getProperty("java.home")
            val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
            val candidate = File(File(home, "bin"), exe)
            return if (candidate.isFile) candidate.path else "java"
        }

        fun parseAngles(spec: String): List<Pair<Float, Float>> =
            spec.split(',').mapNotNull { pair ->
                val parts = pair.trim().split(':')
                if (parts.size != 2) return@mapNotNull null
                val az = parts[0].trim().toFloatOrNull() ?: return@mapNotNull null
                val el = parts[1].trim().toFloatOrNull() ?: return@mapNotNull null
                az to el
            }
    }
}
