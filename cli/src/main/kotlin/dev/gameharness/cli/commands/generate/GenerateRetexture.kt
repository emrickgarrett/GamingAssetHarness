package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Gives an existing GLB a new texture (Meshy retexture), keeping its shape and UV layout: a colourway. */
class GenerateRetexture : CliktCommand(name = "retexture") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val model by option("-m", "--model", help = "Path to the GLB to retexture").required()
    private val description by option("-d", "--description", help = "The new look, in words").default("")
    private val style by option("-s", "--style-image", help = "A local image to take the look from instead of words")
    private val name by option("-n", "--name", help = "Name used for the saved file").default("retextured")
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
            ?: return printError("generate.retexture", "NOT_FOUND", "Workspace '$workspace' not found")
        val modelPath = Path.of(model)
        if (!Files.isRegularFile(modelPath)) return printError("generate.retexture", "INVALID_ARGS", "Model not found: $model")
        if (description.isEmpty() && style == null) {
            return printError("generate.retexture", "INVALID_ARGS", "Give a description or a style image")
        }
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("generate.retexture", "MISSING_KEY", e.message ?: "Missing API key")
        }
        try {
            val styleBytes = style?.let { Files.readAllBytes(Path.of(it)) }
            val task = runBlocking { client.retexture(Files.readAllBytes(modelPath), description, styleBytes) }
            val url = task.modelUrls?.glb ?: return printError("generate.retexture", "GENERATION_FAILED", "No GLB in the result")
            val saved = runBlocking { MeshyAssetSaver(ctx, ws, folder).save(client.download(url, "model"), name) }
            printSuccess("generate.retexture", buildJsonObject {
                put("taskId", task.id)
                put("filePath", saved)
            })
        } catch (e: ApiException) {
            printError("generate.retexture", "GENERATION_FAILED", e.message ?: "Retexture failed")
        } finally {
            client.close()
        }
    }
}
