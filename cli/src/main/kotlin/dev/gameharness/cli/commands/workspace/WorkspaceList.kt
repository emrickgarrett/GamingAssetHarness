package dev.gameharness.cli.commands.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.*

class WorkspaceList : CliktCommand(name = "list") {

    private val ctx by requireObject<CliContext>()

    override fun run() {
        val workspaces = ctx.workspaceManager.listWorkspaces()
        val arr = buildJsonArray {
            for (ws in workspaces) {
                addJsonObject {
                    put("name", ws.name)
                    put("directoryPath", ws.directoryPath)
                    put("createdAt", ws.createdAt)
                    put("assetCount", ws.assets.size)
                    put("folders", buildJsonArray {
                        ws.folders.sorted().forEach { add(it) }
                    })
                }
            }
        }
        printSuccess("workspace.list", buildJsonObject {
            put("workspaces", arr)
        })
    }
}
