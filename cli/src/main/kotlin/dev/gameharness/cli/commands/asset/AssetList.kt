package dev.gameharness.cli.commands.asset

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.assetToJson
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetType
import kotlinx.serialization.json.*

class AssetList : CliktCommand(name = "list") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val type by option("-t", "--type", help = "Filter by type: SPRITE, MODEL_3D, MUSIC, SOUND_EFFECT")
    private val status by option("--status", help = "Filter by status: PENDING, APPROVED, DENIED")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("asset.list", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }

        val typeFilter = type?.let {
            try {
                AssetType.valueOf(it)
            } catch (_: IllegalArgumentException) {
                printError("asset.list", "INVALID_ARGS", "Unknown asset type: $it")
                return
            }
        }
        val statusFilter = status?.let {
            try {
                AssetDecision.valueOf(it)
            } catch (_: IllegalArgumentException) {
                printError("asset.list", "INVALID_ARGS", "Unknown status: $it")
                return
            }
        }

        var assets = ws.assets
        if (typeFilter != null) assets = assets.filter { it.type == typeFilter }
        if (statusFilter != null) assets = assets.filter { it.status == statusFilter }

        val arr = buildJsonArray {
            for (a in assets) {
                add(assetToJson(
                    id = a.id,
                    fileName = a.fileName,
                    filePath = a.filePath,
                    type = a.type.name,
                    format = a.format,
                    description = a.description,
                    sizeBytes = a.sizeBytes,
                    status = a.status.name,
                    folder = a.folder
                ))
            }
        }

        printSuccess("asset.list", buildJsonObject {
            put("workspace", ws.name)
            put("assets", arr)
            put("totalCount", ws.assets.size)
            put("filteredCount", assets.size)
        })
    }
}
