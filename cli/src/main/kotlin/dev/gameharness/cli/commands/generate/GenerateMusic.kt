package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GenerationRequest
import dev.gameharness.core.model.GenerationResult
import kotlinx.coroutines.runBlocking

class GenerateMusic : CliktCommand(name = "music") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val description by option("-d", "--description", help = "Music description").required()
    private val genre by option("--genre", help = "Genre: orchestral, electronic, chiptune, ambient, etc.").default("")
    private val mood by option("--mood", help = "Mood: epic, calm, tense, cheerful, etc.").default("")
    private val instrumental by option("--instrumental", help = "Instrumental only (no vocals)").flag(default = true)
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("generate.music", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        val factory = ClientFactory(ctx.config, ProgressReporter())
        val client = try {
            factory.createMusicClient()
        } catch (e: MissingKeyException) {
            printError("generate.music", "MISSING_KEY", e.message ?: "Missing API key")
            return
        }

        try {
            val params = buildMap {
                if (genre.isNotBlank()) put("genre", genre)
                if (mood.isNotBlank()) put("mood", mood)
                put("instrumental", instrumental.toString())
            }

            val request = GenerationRequest(
                description = description,
                type = AssetType.MUSIC,
                params = params
            )

            val result = runBlocking { client.generate(request) }

            when (result) {
                is GenerationResult.Completed -> {
                    val asset = result.asset.copy(status = AssetDecision.APPROVED, folder = folder)
                    val (path, _) = ctx.workspaceManager.saveAsset(ws, asset, result.fileBytes)
                    val saved = asset.copy(filePath = path.toString(), sizeBytes = result.fileBytes.size.toLong())
                    printSuccess("generate.music", assetToJson(
                        id = saved.id, fileName = saved.fileName, filePath = saved.filePath,
                        type = saved.type.name, format = saved.format, description = saved.description,
                        sizeBytes = saved.sizeBytes, status = saved.status.name, folder = saved.folder
                    ))
                }
                is GenerationResult.Failed -> printError("generate.music", "GENERATION_FAILED", result.error)
                is GenerationResult.InProgress -> printError("generate.music", "INTERNAL_ERROR", "Unexpected InProgress result")
            }
        } catch (e: ApiException.AuthenticationFailed) {
            printError("generate.music", "AUTH_FAILED", e.message ?: "Authentication failed")
        } catch (e: ApiException.RateLimited) {
            printError("generate.music", "RATE_LIMITED", "API rate limit exceeded")
        } catch (e: ApiException.Timeout) {
            printError("generate.music", "TIMEOUT", e.message ?: "Request timed out")
        } catch (e: ApiException.NetworkError) {
            printError("generate.music", "NETWORK_ERROR", e.message ?: "Network error")
        } catch (e: ApiException) {
            printError("generate.music", "GENERATION_FAILED", e.message ?: "Generation failed")
        } finally {
            client.close()
        }
    }
}
