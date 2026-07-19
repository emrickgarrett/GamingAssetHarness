package dev.gameharness.cli.commands.asset

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import dev.gameharness.api.gemini.GeminiClient
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GeneratedAsset
import dev.gameharness.core.model.Workspace
import dev.gameharness.core.util.SpriteSheetSplitter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.imageio.ImageIO

/**
 * Repairs residual chroma-key artifacts on an already-generated sprite:
 * un-mixes the faint key-colored halo left along anti-aliased edges
 * ([SpriteSheetSplitter.decontaminateEdges]) and re-trims the transparent
 * border with an alpha threshold so near-invisible halo pixels don't
 * inflate the bounding box.
 *
 * The chroma key is re-derived from the asset's stored description by
 * default (the same selection used at generation time); pass --key to
 * override.
 */
class AssetCleanup : CliktCommand(name = "cleanup") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val asset by option("-a", "--asset", help = "Asset filename (exact or partial match)").required()
    private val key by option(
        "--key",
        help = "Chroma key that was used at generation time (default: auto from description)"
    ).choice("auto", "green", "magenta", "blue").default("auto")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("asset.cleanup", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        val matchingAssets = findMatchingAssets(ws, asset)
        if (matchingAssets.isEmpty()) {
            printError(
                "asset.cleanup", "ASSET_NOT_FOUND",
                "No asset matching '$asset' found in workspace '$workspace'"
            )
            return
        }
        if (matchingAssets.size > 1) {
            val names = matchingAssets.joinToString(", ") { it.fileName }
            printError(
                "asset.cleanup", "AMBIGUOUS_MATCH",
                "Multiple assets match '$asset': $names. Use exact filename."
            )
            return
        }
        val original = matchingAssets.single()

        if (original.type != AssetType.SPRITE) {
            printError(
                "asset.cleanup", "UNSUPPORTED_TYPE",
                "Only sprites can be cleaned up. '${original.fileName}' is a ${original.type.displayName}."
            )
            return
        }
        if (!original.fileName.endsWith(".png", ignoreCase = true)) {
            printError(
                "asset.cleanup", "UNSUPPORTED_FORMAT",
                "Cleanup requires an alpha channel. '${original.fileName}' is not a PNG."
            )
            return
        }

        val file = File(original.filePath)
        if (!file.exists()) {
            printError(
                "asset.cleanup", "FILE_NOT_FOUND",
                "Asset file not found at: ${original.filePath}"
            )
            return
        }

        val chromaKey = when (key) {
            "green" -> GeminiClient.CHROMA_GREEN
            "magenta" -> GeminiClient.CHROMA_MAGENTA
            "blue" -> GeminiClient.CHROMA_BLUE
            else -> GeminiClient.selectChromaKeyColor(original.description)
        }

        try {
            val image = ImageIO.read(file)
                ?: throw IllegalStateException("Could not decode image: ${original.filePath}")

            // Full re-key pass: remove background by key-channel dominance
            // (robust to off-key hue drift, structurally can't eat dark
            // outlines), measure the real border color for tint un-mixing,
            // then defringe, un-mix residual tint, and drop noise specks.
            val bgColor = SpriteSheetSplitter.estimateBorderColor(image, chromaKey.color)
                ?: chromaKey.color
            var cleaned = SpriteSheetSplitter.removeBackgroundKeyness(
                image,
                bgColor = chromaKey.color
            )
            repeat(GeminiClient.DEFRINGE_PASSES) {
                cleaned = SpriteSheetSplitter.defringeEdges(
                    cleaned,
                    bgColor = bgColor,
                    tolerance = GeminiClient.DEFRINGE_TOLERANCE
                )
            }
            cleaned = SpriteSheetSplitter.decontaminateEdges(
                cleaned,
                bgColor = bgColor,
                maxDepth = GeminiClient.decontaminateDepth(cleaned.width, cleaned.height)
            )
            cleaned = SpriteSheetSplitter.removeSmallIslands(
                cleaned,
                minArea = GeminiClient.islandMinArea(cleaned.width, cleaned.height)
            )
            val result = SpriteSheetSplitter.trimTransparent(cleaned, alphaThreshold = TRIM_ALPHA_THRESHOLD)
            val originalSizeBytes = file.length()

            val cleanedBytes = SpriteSheetSplitter.tileToBytes(result.image)
            ctx.workspaceManager.replaceAssetFile(ws, original.id, cleanedBytes)

            printSuccess("asset.cleanup", buildCleanupJson(
                original, chromaKey.name,
                String.format("#%02x%02x%02x", bgColor.red, bgColor.green, bgColor.blue),
                result.originalWidth, result.originalHeight,
                result.trimmedWidth, result.trimmedHeight,
                originalSizeBytes, cleanedBytes.size.toLong()
            ))
        } catch (e: Exception) {
            printError("asset.cleanup", "CLEANUP_FAILED", e.message ?: "Failed to clean up image")
        }
    }

    private fun findMatchingAssets(ws: Workspace, query: String): List<GeneratedAsset> {
        val exact = ws.assets.filter { it.fileName == query }
        if (exact.isNotEmpty()) return exact
        return ws.assets.filter { it.fileName.contains(query, ignoreCase = true) }
    }

    private fun buildCleanupJson(
        asset: GeneratedAsset,
        chromaKeyName: String,
        measuredBg: String,
        originalWidth: Int, originalHeight: Int,
        trimmedWidth: Int, trimmedHeight: Int,
        originalSizeBytes: Long, cleanedSizeBytes: Long
    ): JsonObject = buildJsonObject {
        put("assetId", asset.id)
        put("fileName", asset.fileName)
        put("filePath", asset.filePath)
        put("chromaKey", chromaKeyName)
        put("measuredBg", measuredBg)
        put("originalWidth", originalWidth)
        put("originalHeight", originalHeight)
        put("trimmedWidth", trimmedWidth)
        put("trimmedHeight", trimmedHeight)
        put("originalSizeBytes", originalSizeBytes)
        put("cleanedSizeBytes", cleanedSizeBytes)
    }

    companion object {
        /** Pixels below this alpha don't count as content when re-trimming. */
        const val TRIM_ALPHA_THRESHOLD = 16
    }
}
