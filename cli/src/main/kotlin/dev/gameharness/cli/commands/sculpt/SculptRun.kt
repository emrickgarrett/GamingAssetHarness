package dev.gameharness.cli.commands.sculpt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.api.img2threejs.SculptRunner
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * `harness sculpt run <script> [args...]` — run one forge script.
 *
 * `sculpt next` prints the exact command the pipeline wants next, and that
 * command is always "python forge/<stage>/<script>.py ...". Rather than
 * hand-wrapping forty-odd scripts (and drifting every time upstream adds one),
 * this executes them through the same resolved checkout and interpreter, and
 * reports the result as JSON. Wrapper commands exist only where the harness
 * adds something of its own — `init`, `next`, `bake`.
 *
 *   harness sculpt run forge/stage3_build/generate_threejs_factory.py spec.json --out model.ts
 */
class SculptRun : CliktCommand(
    name = "run",
    help = "Run a forge script (usually the command reported by 'sculpt next')"
) {
    private val script by argument("script", help = "Script path relative to the checkout, e.g. forge/next.py")
    private val args by argument("args", help = "Arguments passed through to the script").multiple()
    private val forge by option("--forge", help = "Path to the img2threejs checkout")

    override fun run() {
        val runner = try {
            SculptRunner(resolveForgeRoot(forge))
        } catch (e: SculptRunner.SculptException) {
            printError("sculpt.run", "FORGE_MISSING", e.message ?: "img2threejs not available")
            return
        }

        // Scripts run with the CHECKOUT as their working directory (so the
        // commands next.py prints stay valid), which would otherwise resolve
        // the caller's relative paths against the wrong root. Absolutise any
        // argument that actually names an existing file here — untouched
        // otherwise, so flags and values are passed through verbatim.
        val resolved = args.map { a ->
            val f = java.io.File(a)
            if (!f.isAbsolute && f.exists()) f.absolutePath else a
        }

        val out = try {
            runner.script(script, *resolved.toTypedArray())
        } catch (e: SculptRunner.SculptException) {
            printError("sculpt.run", "SCRIPT_ERROR", e.message ?: "script failed")
            return
        }

        // a non-zero exit is frequently a GATE rejecting the pass, not a crash —
        // surface it as data (with stdout/stderr intact) so the agent can react
        printSuccess(
            "sculpt.run",
            buildJsonObject {
                put("script", JsonPrimitive(script))
                put("exitCode", JsonPrimitive(out.exitCode))
                put("passed", JsonPrimitive(out.ok))
                put("stdout", JsonPrimitive(out.stdout.trim()))
                put("stderr", JsonPrimitive(out.stderr.trim()))
            }
        )
    }
}
