package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Applies Meshy animation-library actions (by action ID) to a character rigged with
 * `generate rig`, saving one GLB that holds a clip per action, in the order given.
 */
class GenerateAnimation : CliktCommand(name = "animation") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val rigTask by option("-r", "--rig-task", help = "rigTaskId printed by 'generate rig'").required()
    private val actions by option("-a", "--actions", help = "Comma-separated animation library action IDs (1-10)").required()
    private val name by option("-n", "--name", help = "Name used for the saved file").default("character animations")
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
            ?: return printError("generate.animation", "NOT_FOUND", "Workspace '$workspace' not found")
        val ids = actions.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (ids.isEmpty() || ids.size > 10 || ids.size != ids.toSet().size) {
            return printError("generate.animation", "INVALID_ARGS", "Give 1-10 distinct numeric action IDs")
        }
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("generate.animation", "MISSING_KEY", e.message ?: "Missing API key")
        }

        try {
            val url = runBlocking { client.animate(rigTask, ids) }
            val bytes = runBlocking { client.download(url, "animation") }
            val path = MeshyAssetSaver(ctx, ws, folder).save(bytes, name)
            printSuccess("generate.animation", buildJsonObject {
                put("filePath", path)
                putJsonArray("actions") { ids.forEach { add(it) } }
            })
        } catch (e: ApiException.AuthenticationFailed) {
            printError("generate.animation", "AUTH_FAILED", e.message ?: "Authentication failed")
        } catch (e: ApiException.RateLimited) {
            printError("generate.animation", "RATE_LIMITED", "API rate limit exceeded")
        } catch (e: ApiException.Timeout) {
            printError("generate.animation", "TIMEOUT", e.message ?: "Request timed out")
        } catch (e: ApiException) {
            printError("generate.animation", "GENERATION_FAILED", e.message ?: "Animation failed")
        } finally {
            client.close()
        }
    }
}
