package dev.gameharness.cli.commands.asset

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
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

class AssetTrim : CliktCommand(name = "trim") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val asset by option("-a", "--asset", help = "Asset filename (exact or partial match)").required()

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("asset.trim", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        // Find asset by filename match
        val matchingAssets = findMatchingAssets(ws, asset)
        if (matchingAssets.isEmpty()) {
            printError(
                "asset.trim", "ASSET_NOT_FOUND",
                "No asset matching '$asset' found in workspace '$workspace'"
            )
            return
        }
        if (matchingAssets.size > 1) {
            val names = matchingAssets.joinToString(", ") { it.fileName }
            printError(
                "asset.trim", "AMBIGUOUS_MATCH",
                "Multiple assets match '$asset': $names. Use exact filename."
            )
            return
        }
        val original = matchingAssets.single()

        // Only sprites support trimming
        if (original.type != AssetType.SPRITE) {
            printError(
                "asset.trim", "UNSUPPORTED_TYPE",
                "Only sprites can be trimmed. '${original.fileName}' is a ${original.type.displayName}."
            )
            return
        }

        // Validate file exists on disk
        val file = File(original.filePath)
        if (!file.exists()) {
            printError(
                "asset.trim", "FILE_NOT_FOUND",
                "Asset file not found at: ${original.filePath}"
            )
            return
        }

        try {
            val image = ImageIO.read(file)
                ?: throw IllegalStateException("Could not decode image: ${original.filePath}")

            val result = SpriteSheetSplitter.trimTransparent(image)
            val originalSizeBytes = file.length()

            if (!result.wasTrimmed) {
                printSuccess("asset.trim", buildTrimJson(
                    original, result.originalWidth, result.originalHeight,
                    result.trimmedWidth, result.trimmedHeight,
                    originalSizeBytes, originalSizeBytes, wasTrimmed = false
                ))
                return
            }

            // Encode trimmed image and replace file
            val trimmedBytes = SpriteSheetSplitter.tileToBytes(result.image)
            ctx.workspaceManager.replaceAssetFile(ws, original.id, trimmedBytes)

            printSuccess("asset.trim", buildTrimJson(
                original, result.originalWidth, result.originalHeight,
                result.trimmedWidth, result.trimmedHeight,
                originalSizeBytes, trimmedBytes.size.toLong(), wasTrimmed = true
            ))
        } catch (e: Exception) {
            printError("asset.trim", "TRIM_FAILED", e.message ?: "Failed to trim image")
        }
    }

    private fun findMatchingAssets(ws: Workspace, query: String): List<GeneratedAsset> {
        val exact = ws.assets.filter { it.fileName == query }
        if (exact.isNotEmpty()) return exact
        return ws.assets.filter { it.fileName.contains(query, ignoreCase = true) }
    }

    private fun buildTrimJson(
        asset: GeneratedAsset,
        originalWidth: Int, originalHeight: Int,
        trimmedWidth: Int, trimmedHeight: Int,
        originalSizeBytes: Long, trimmedSizeBytes: Long,
        wasTrimmed: Boolean
    ): JsonObject = buildJsonObject {
        put("assetId", asset.id)
        put("fileName", asset.fileName)
        put("filePath", asset.filePath)
        put("originalWidth", originalWidth)
        put("originalHeight", originalHeight)
        put("trimmedWidth", trimmedWidth)
        put("trimmedHeight", trimmedHeight)
        put("originalSizeBytes", originalSizeBytes)
        put("trimmedSizeBytes", trimmedSizeBytes)
        put("wasTrimmed", wasTrimmed)
    }
}
