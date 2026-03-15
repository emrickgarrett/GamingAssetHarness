package dev.gameharness.cli.commands.workspace

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class WorkspaceOpen : CliktCommand(name = "open") {

    private val ctx by requireObject<CliContext>()
    private val directory by option("-p", "--directory", help = "Path to existing directory").required()
    private val name by option("-n", "--name", help = "Workspace display name (defaults to folder name)")

    override fun run() {
        try {
            val dirPath = Path.of(directory)
            val wsName = name ?: dirPath.fileName?.toString() ?: "Unnamed"
            val ws = ctx.workspaceManager.openWorkspace(wsName, dirPath)
            printSuccess("workspace.open", buildJsonObject {
                put("name", ws.name)
                put("directoryPath", ws.directoryPath)
                put("createdAt", ws.createdAt)
                put("assetCount", ws.assets.size)
            })
        } catch (e: IllegalArgumentException) {
            printError("workspace.open", "INVALID_ARGS", e.message ?: "Invalid arguments")
        } catch (e: Exception) {
            printError("workspace.open", "INTERNAL_ERROR", e.message ?: "Failed to open workspace")
        }
    }
}
