package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Makes an animation from a description (Meshy text-to-motion) and applies it to a rigged character, for motions
 * the animation library does not have (a two-handed axe chop). Saves the GLB of the character with the clip.
 */
class GenerateMotion : CliktCommand(name = "motion") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val rigTask by option("-r", "--rig-task", help = "rigTaskId printed by 'generate rig'").required()
    private val prompt by option("-p", "--prompt", help = "The motion, in words (at most 400 characters)").required()
    private val seconds by option("-s", "--seconds", help = "Length, 2 to 10 seconds").double().default(3.0)
    private val name by option("-n", "--name", help = "Name used for the saved file").default("motion")
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
            ?: return printError("generate.motion", "NOT_FOUND", "Workspace '$workspace' not found")
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("generate.motion", "MISSING_KEY", e.message ?: "Missing API key")
        }
        try {
            val url = runBlocking { client.motion(rigTask, prompt, seconds) }
            val saved = runBlocking { MeshyAssetSaver(ctx, ws, folder).save(client.download(url, "motion"), name) }
            printSuccess("generate.motion", buildJsonObject { put("filePath", saved) })
        } catch (e: ApiException) {
            printError("generate.motion", "GENERATION_FAILED", e.message ?: "Motion failed")
        } catch (e: IllegalArgumentException) {
            printError("generate.motion", "INVALID_ARGS", e.message ?: "Invalid arguments")
        } finally {
            client.close()
        }
    }
}
