package dev.gameharness.cli.commands.sculpt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.api.img2threejs.SculptRunner
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * `harness sculpt next` — what should the agent do right now?
 *
 * Wraps `forge/next.py`, which reads the pass state out of the spec and prints
 * the current pass, the exact command to run, and the acceptance criteria still
 * unmet. Re-emitted as JSON so an agent can act on it without scraping text.
 */
class SculptNext : CliktCommand(name = "next", help = "Report the current pass and the exact next command") {

    private val spec by option("-s", "--spec", help = "Path to the sculpt spec JSON").required()
    private val forge by forgeOption()

    override fun run() {
        val specFile = File(spec).absoluteFile
        if (!specFile.isFile) {
            printError("sculpt.next", "NOT_FOUND", "Spec not found: ${specFile.path}")
            return
        }
        val runner = try {
            SculptRunner(resolveForgeRoot(forge))
        } catch (e: SculptRunner.SculptException) {
            printError("sculpt.next", "FORGE_MISSING", e.message ?: "img2threejs not available")
            return
        }

        val out = runner.next(specFile)
        if (!out.ok) {
            printError("sculpt.next", "FORGE_ERROR", out.stderr.ifBlank { out.stdout }.trim())
            return
        }

        // next.py prints: "current pass: X", "next command: Y", then a bulleted
        // list of unmet criteria. Parse it, but keep the raw text too so nothing
        // is lost if the upstream format shifts.
        val lines = out.stdout.lines().map(String::trim).filter(String::isNotEmpty)
        val currentPass = lines.firstOrNull { it.startsWith("current pass:") }?.substringAfter(":")?.trim()
        val nextCommand = lines.firstOrNull { it.startsWith("next command:") }?.substringAfter(":")?.trim()
        val unmet = lines.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
        val complete = lines.any { it.contains("pipeline: complete") }

        printSuccess(
            "sculpt.next",
            buildJsonObject {
                put("spec", JsonPrimitive(specFile.path))
                put("complete", JsonPrimitive(complete))
                put("currentPass", JsonPrimitive(currentPass ?: if (complete) "complete" else "unknown"))
                put("nextCommand", JsonPrimitive(nextCommand ?: ""))
                put("unmetCriteria", JsonArray(unmet.map(::JsonPrimitive)))
                put("raw", JsonPrimitive(out.stdout.trim()))
            }
        )
    }
}
