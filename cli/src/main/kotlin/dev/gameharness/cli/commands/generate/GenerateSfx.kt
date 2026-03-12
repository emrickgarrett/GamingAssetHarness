package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GenerationRequest
import dev.gameharness.core.model.GenerationResult
import kotlinx.coroutines.runBlocking

class GenerateSfx : CliktCommand(name = "sfx") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val description by option("-d", "--description", help = "Sound effect description").required()
    private val duration by option("--duration", help = "Duration in seconds (0.5-22.0)").double().default(2.0)
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("generate.sfx", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        val factory = ClientFactory(ctx.config, ProgressReporter())
        val client = try {
            factory.createSfxClient()
        } catch (e: MissingKeyException) {
            printError("generate.sfx", "MISSING_KEY", e.message ?: "Missing API key")
            return
        }

        try {
            val request = GenerationRequest(
                description = description,
                type = AssetType.SOUND_EFFECT,
                params = mapOf("durationSeconds" to duration.toString())
            )

            val result = runBlocking { client.generate(request) }

            when (result) {
                is GenerationResult.Completed -> {
                    val asset = result.asset.copy(status = AssetDecision.APPROVED, folder = folder)
                    val (path, _) = ctx.workspaceManager.saveAsset(ws, asset, result.fileBytes)
                    val saved = asset.copy(filePath = path.toString(), sizeBytes = result.fileBytes.size.toLong())
                    printSuccess("generate.sfx", assetToJson(
                        id = saved.id, fileName = saved.fileName, filePath = saved.filePath,
                        type = saved.type.name, format = saved.format, description = saved.description,
                        sizeBytes = saved.sizeBytes, status = saved.status.name, folder = saved.folder
                    ))
                }
                is GenerationResult.Failed -> printError("generate.sfx", "GENERATION_FAILED", result.error)
                is GenerationResult.InProgress -> printError("generate.sfx", "INTERNAL_ERROR", "Unexpected InProgress result")
            }
        } catch (e: ApiException.AuthenticationFailed) {
            printError("generate.sfx", "AUTH_FAILED", e.message ?: "Authentication failed")
        } catch (e: ApiException.RateLimited) {
            printError("generate.sfx", "RATE_LIMITED", "API rate limit exceeded")
        } catch (e: ApiException.Timeout) {
            printError("generate.sfx", "TIMEOUT", e.message ?: "Request timed out")
        } catch (e: ApiException.NetworkError) {
            printError("generate.sfx", "NETWORK_ERROR", e.message ?: "Network error")
        } catch (e: ApiException) {
            printError("generate.sfx", "GENERATION_FAILED", e.message ?: "Generation failed")
        } finally {
            client.close()
        }
    }
}
