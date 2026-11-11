package dev.gameharness.cli.commands.sculpt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.api.img2threejs.SculptRunner
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * `harness sculpt init` — opens a sculpt session for one reference image.
 *
 * Validates the interpreter and the image (stage1 `probe_image.py` rejects
 * references the pipeline can't work from), then lays out a session directory
 * inside the workspace and reports the next step. It deliberately does NOT
 * author the spec: that is the agent's job (stage2 is model-driven), and
 * guessing one here would trip the strict-quality gate downstream.
 */
class SculptInit : CliktCommand(name = "init", help = "Start a sculpt session from a reference image") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val image by option("-i", "--image", help = "Reference image (PNG)").required()
    private val name by option("-n", "--name", help = "Object name, e.g. 'rum-barrel'").required()
    private val forge by forgeOption()

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("sculpt.init", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }
        val reference = File(image).absoluteFile
        if (!reference.isFile) {
            printError("sculpt.init", "NOT_FOUND", "Reference image not found: ${reference.path}")
            return
        }

        val runner = try {
            SculptRunner(resolveForgeRoot(forge))
        } catch (e: SculptRunner.SculptException) {
            printError("sculpt.init", "FORGE_MISSING", e.message ?: "img2threejs not available")
            return
        }

        val python = try {
            runner.checkPython()
        } catch (e: SculptRunner.SculptException) {
            printError("sculpt.init", "PYTHON_MISSING", e.message ?: "Python 3.10+ required")
            return
        }

        // stage1 gate: a reference the pipeline can't use should fail here, not
        // eight passes later
        val probe = runner.script("forge/stage1_intake/probe_image.py", reference.path)
        if (!probe.ok) {
            printError(
                "sculpt.init",
                "UNSUITABLE_REFERENCE",
                "probe_image rejected ${reference.name}: ${probe.stderr.ifBlank { probe.stdout }.trim()}"
            )
            return
        }

        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val sessionDir = File(File(ws.directoryPath), "sculpts/$slug").apply { mkdirs() }
        val refCopy = File(sessionDir, "reference.${reference.extension.ifBlank { "png" }}")
        reference.copyTo(refCopy, overwrite = true)

        printSuccess(
            "sculpt.init",
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("slug", JsonPrimitive(slug))
                put("sessionDir", JsonPrimitive(sessionDir.path))
                put("reference", JsonPrimitive(refCopy.path))
                put("specPath", JsonPrimitive(File(sessionDir, "$slug.spec.json").path))
                put("python", JsonPrimitive(python))
                put("probe", JsonPrimitive(probe.stdout.trim()))
                // stage2 is model-driven; tell the agent exactly what to run next
                put(
                    "nextStep",
                    JsonPrimitive(
                        "Author the spec: python forge/stage2_spec/new_pre_spec_assessment.py " +
                            "then new_sculpt_spec.py \"$name\" --image ${refCopy.path} " +
                            "--out ${File(sessionDir, "$slug.spec.json").path}"
                    )
                )
            }
        )
    }
}
