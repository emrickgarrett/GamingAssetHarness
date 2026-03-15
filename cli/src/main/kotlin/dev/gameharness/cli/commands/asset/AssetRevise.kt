package dev.gameharness.cli.commands.asset

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import dev.gameharness.core.model.*
import kotlinx.coroutines.runBlocking

class AssetRevise : CliktCommand(name = "revise") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val asset by option("-a", "--asset", help = "Asset filename (exact or partial match)").required()
    private val description by option("-d", "--description", help = "Revision instructions").required()
    private val style by option("-s", "--style", help = "Art style override (default: inherit from original)")
    private val aspectRatio by option("--aspect-ratio", help = "Aspect ratio override (default: inherit)")
    private val imageSize by option("--image-size", help = "Resolution preset: 512, 1K, 2K, 4K")
    private val width by option("--width", help = "Width in pixels (hint)").int()
    private val height by option("--height", help = "Height in pixels (hint)").int()
    private val noBgRemoval by option("--no-bg-removal", help = "Skip background removal").flag()
    private val folder by option("-f", "--folder", help = "Virtual folder (default: inherit from original)")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("asset.revise", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        // Find asset by filename match
        val matchingAssets = findMatchingAssets(ws, asset)
        if (matchingAssets.isEmpty()) {
            printError(
                "asset.revise", "ASSET_NOT_FOUND",
                "No asset matching '$asset' found in workspace '$workspace'"
            )
            return
        }
        if (matchingAssets.size > 1) {
            val names = matchingAssets.joinToString(", ") { it.fileName }
            printError(
                "asset.revise", "AMBIGUOUS_MATCH",
                "Multiple assets match '$asset': $names. Use exact filename."
            )
            return
        }
        val original = matchingAssets.single()

        // Only sprites support revision via reference images
        if (original.type != AssetType.SPRITE) {
            printError(
                "asset.revise", "UNSUPPORTED_TYPE",
                "Only sprites can be revised. '${original.fileName}' is a ${original.type.displayName}."
            )
            return
        }

        // Validate file exists on disk
        val originalFile = java.nio.file.Path.of(original.filePath)
        if (!java.nio.file.Files.exists(originalFile)) {
            printError(
                "asset.revise", "FILE_NOT_FOUND",
                "Original asset file not found at: ${original.filePath}"
            )
            return
        }

        val factory = ClientFactory(ctx.config, ProgressReporter())
        val client = try {
            factory.createSpriteClient()
        } catch (e: MissingKeyException) {
            printError("asset.revise", "MISSING_KEY", e.message ?: "Missing API key")
            return
        }

        try {
            // Inherit params from original, allow overrides
            val effectiveStyle = style ?: original.generationParams["style"] ?: "16bit"
            val effectiveAspectRatio = aspectRatio ?: original.generationParams["aspectRatio"] ?: "1:1"
            val effectiveFolder = folder ?: original.folder

            val revisionDescription =
                "Revise this sprite: $description. Original description: ${original.description}"

            val request = GenerationRequest(
                description = revisionDescription,
                type = AssetType.SPRITE,
                params = buildMap {
                    put("style", effectiveStyle)
                    put("aspectRatio", effectiveAspectRatio)
                    if (imageSize != null) put("imageSize", imageSize!!)
                    val w = width ?: original.generationParams["width"]?.toIntOrNull()
                    val h = height ?: original.generationParams["height"]?.toIntOrNull()
                    if (w != null) put("width", w.toString())
                    if (h != null) put("height", h.toString())
                    if (noBgRemoval) {
                        put("removeBg", "false")
                    } else {
                        val originalRemoveBg = original.generationParams["removeBg"]
                        if (originalRemoveBg != null) put("removeBg", originalRemoveBg)
                    }
                },
                referenceImagePaths = listOf(original.filePath)
            )

            val result = runBlocking { client.generate(request) }

            when (result) {
                is GenerationResult.Completed -> {
                    val newAsset = result.asset.copy(
                        status = AssetDecision.APPROVED,
                        folder = effectiveFolder
                    )
                    val (path, _) = ctx.workspaceManager.saveAsset(ws, newAsset, result.fileBytes)
                    val saved = newAsset.copy(
                        filePath = path.toString(),
                        sizeBytes = result.fileBytes.size.toLong()
                    )
                    printSuccess(
                        "asset.revise",
                        revisionAssetToJson(saved, original.fileName)
                    )
                }

                is GenerationResult.Failed ->
                    printError("asset.revise", "GENERATION_FAILED", result.error)

                is GenerationResult.InProgress ->
                    printError("asset.revise", "INTERNAL_ERROR", "Unexpected InProgress result")
            }
        } catch (e: ApiException.AuthenticationFailed) {
            printError("asset.revise", "AUTH_FAILED", e.message ?: "Authentication failed")
        } catch (e: ApiException.RateLimited) {
            printError("asset.revise", "RATE_LIMITED", "API rate limit exceeded")
        } catch (e: ApiException.Timeout) {
            printError("asset.revise", "TIMEOUT", e.message ?: "Request timed out")
        } catch (e: ApiException.NetworkError) {
            printError("asset.revise", "NETWORK_ERROR", e.message ?: "Network error")
        } catch (e: ApiException) {
            printError("asset.revise", "GENERATION_FAILED", e.message ?: "Generation failed")
        } finally {
            client.close()
        }
    }

    private fun findMatchingAssets(ws: Workspace, query: String): List<GeneratedAsset> {
        // Exact match first
        val exact = ws.assets.filter { it.fileName == query }
        if (exact.isNotEmpty()) return exact

        // Substring match (case-insensitive)
        return ws.assets.filter { it.fileName.contains(query, ignoreCase = true) }
    }
}
