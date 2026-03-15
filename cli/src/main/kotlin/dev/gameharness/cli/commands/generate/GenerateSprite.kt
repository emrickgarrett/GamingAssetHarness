package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GenerationRequest
import dev.gameharness.core.model.GenerationResult
import kotlinx.coroutines.runBlocking

class GenerateSprite : CliktCommand(name = "sprite") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val description by option("-d", "--description", help = "Sprite description").required()
    private val style by option("-s", "--style", help = "Art style: 8bit, 16bit, modern, realistic").default("16bit")
    private val aspectRatio by option("--aspect-ratio", help = "Aspect ratio: 1:1, 2:3, 3:2, 3:4, 4:3, 4:5, 5:4, 9:16, 16:9, 21:9").default("1:1")
    private val imageSize by option("--image-size", help = "Resolution preset: 512, 1K, 2K, 4K")
    private val width by option("--width", help = "Desired width in pixels (hint, not exact)").int()
    private val height by option("--height", help = "Desired height in pixels (hint, not exact)").int()
    private val noBgRemoval by option("--no-bg-removal", help = "Skip automatic green background removal (keeps opaque background)").flag()
    private val reference by option("-r", "--reference", help = "Reference image path").multiple()
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("generate.sprite", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        val factory = ClientFactory(ctx.config, ProgressReporter())
        val client = try {
            factory.createSpriteClient()
        } catch (e: MissingKeyException) {
            printError("generate.sprite", "MISSING_KEY", e.message ?: "Missing API key")
            return
        }

        try {
            val request = GenerationRequest(
                description = description,
                type = AssetType.SPRITE,
                params = buildMap {
                    put("style", style)
                    put("aspectRatio", aspectRatio)
                    if (imageSize != null) put("imageSize", imageSize!!)
                    if (width != null) put("width", width.toString())
                    if (height != null) put("height", height.toString())
                    if (noBgRemoval) put("removeBg", "false")
                },
                referenceImagePaths = reference
            )

            val result = runBlocking { client.generate(request) }

            when (result) {
                is GenerationResult.Completed -> {
                    val asset = result.asset.copy(status = AssetDecision.APPROVED, folder = folder)
                    val (path, _) = ctx.workspaceManager.saveAsset(ws, asset, result.fileBytes)
                    val saved = asset.copy(filePath = path.toString(), sizeBytes = result.fileBytes.size.toLong())
                    printSuccess("generate.sprite", assetToJson(
                        id = saved.id, fileName = saved.fileName, filePath = saved.filePath,
                        type = saved.type.name, format = saved.format, description = saved.description,
                        sizeBytes = saved.sizeBytes, status = saved.status.name, folder = saved.folder
                    ))
                }
                is GenerationResult.Failed -> printError("generate.sprite", "GENERATION_FAILED", result.error)
                is GenerationResult.InProgress -> printError("generate.sprite", "INTERNAL_ERROR", "Unexpected InProgress result")
            }
        } catch (e: ApiException.AuthenticationFailed) {
            printError("generate.sprite", "AUTH_FAILED", e.message ?: "Authentication failed")
        } catch (e: ApiException.RateLimited) {
            printError("generate.sprite", "RATE_LIMITED", "API rate limit exceeded")
        } catch (e: ApiException.Timeout) {
            printError("generate.sprite", "TIMEOUT", e.message ?: "Request timed out")
        } catch (e: ApiException.NetworkError) {
            printError("generate.sprite", "NETWORK_ERROR", e.message ?: "Network error")
        } catch (e: ApiException) {
            printError("generate.sprite", "GENERATION_FAILED", e.message ?: "Generation failed")
        } finally {
            client.close()
        }
    }
}
