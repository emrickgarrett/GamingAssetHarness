package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Makes a 3D model from local images: one view (Meshy image-to-3D) or 2-4 views of the same object, say front,
 * side and back (multi-image-to-3D), which keeps every side true to the pictures. Saves the GLB and prints the
 * task ID and the credits Meshy charged.
 */
class GenerateFromImages : CliktCommand(name = "from-images") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val images by option("-i", "--image", help = "Local image file (repeat for 2-4 views of one object)").multiple(required = true)
    private val name by option("-n", "--name", help = "Name used for the saved file").default("model")
    private val poseMode by option("--pose-mode", help = "t-pose or a-pose (for anything to be rigged)").choice("t-pose", "a-pose")
    private val polycount by option("--polycount", help = "Remesh to about this many polygons (0: no remesh)").int().default(0)
    private val texturePrompt by option("--texture-prompt", help = "Extra words to guide the texture").default("")
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
            ?: return printError("generate.from-images", "NOT_FOUND", "Workspace '$workspace' not found")
        if (images.size > 4) return printError("generate.from-images", "INVALID_ARGS", "At most 4 images")
        val bytes = images.map { Path.of(it) }.map {
            if (!Files.isRegularFile(it)) return printError("generate.from-images", "INVALID_ARGS", "Image not found: $it")
            Files.readAllBytes(it)
        }
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("generate.from-images", "MISSING_KEY", e.message ?: "Missing API key")
        }
        try {
            val task = runBlocking { client.fromImages(bytes, poseMode ?: "", polycount, texturePrompt) }
            val url = task.modelUrls?.glb ?: return printError("generate.from-images", "GENERATION_FAILED", "No GLB in the result")
            val saved = runBlocking { MeshyAssetSaver(ctx, ws, folder).save(client.download(url, "model"), name) }
            printSuccess("generate.from-images", buildJsonObject {
                put("taskId", task.id)
                put("filePath", saved)
            })
        } catch (e: ApiException) {
            printError("generate.from-images", "GENERATION_FAILED", e.message ?: "Generation failed")
        } finally {
            client.close()
        }
    }
}
