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

class WorkspaceCreate : CliktCommand(name = "create") {

    private val ctx by requireObject<CliContext>()
    private val name by option("-n", "--name", help = "Workspace display name").required()
    private val directory by option("-p", "--directory", help = "Filesystem path for the workspace").required()

    override fun run() {
        try {
            val ws = ctx.workspaceManager.createWorkspace(name, Path.of(directory))
            printSuccess("workspace.create", buildJsonObject {
                put("name", ws.name)
                put("directoryPath", ws.directoryPath)
                put("createdAt", ws.createdAt)
            })
        } catch (e: IllegalArgumentException) {
            printError("workspace.create", "INVALID_ARGS", e.message ?: "Invalid arguments")
        } catch (e: Exception) {
            printError("workspace.create", "INTERNAL_ERROR", e.message ?: "Failed to create workspace")
        }
    }
}
