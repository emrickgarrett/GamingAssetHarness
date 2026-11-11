package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import dev.gameharness.api.common.ApiException
import dev.gameharness.api.common.generateAssetIdentifiers
import dev.gameharness.cli.*
import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GeneratedAsset
import dev.gameharness.core.model.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Auto-rigs a humanoid GLB with Meshy and saves the rigged model plus Meshy's free walking and
 * running clips into the workspace. The printed `rigTaskId` is what `generate animation` needs.
 */
class GenerateRig : CliktCommand(name = "rig") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val model by option("-m", "--model", help = "Path to a humanoid GLB facing +Z").required()
    private val height by option("--height", help = "Character height in metres").double().default(1.7)
    private val name by option("-n", "--name", help = "Name used for the saved files").default("character")
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
            ?: return printError("generate.rig", "NOT_FOUND", "Workspace '$workspace' not found")
        val modelPath = Path.of(model)
        if (!Files.isRegularFile(modelPath)) {
            return printError("generate.rig", "INVALID_ARGS", "Model file not found: $model")
        }
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("generate.rig", "MISSING_KEY", e.message ?: "Missing API key")
        }

        try {
            val outcome = runBlocking { client.rig(Files.readAllBytes(modelPath), height) }
            val saver = MeshyAssetSaver(ctx, ws, folder)
            val rigged = runBlocking { saver.save(client.download(outcome.riggedGlbUrl, "rigged model"), "$name rigged") }
            val walking = outcome.walkingGlbUrl?.let { url ->
                runBlocking { saver.save(client.download(url, "walking clip"), "$name walking") }
            }
            val running = outcome.runningGlbUrl?.let { url ->
                runBlocking { saver.save(client.download(url, "running clip"), "$name running") }
            }
            printSuccess("generate.rig", buildJsonObject {
                put("rigTaskId", outcome.rigTaskId)
                put("rigged", rigged)
                walking?.let { put("walking", it) }
                running?.let { put("running", it) }
            })
        } catch (e: ApiException.AuthenticationFailed) {
            printError("generate.rig", "AUTH_FAILED", e.message ?: "Authentication failed")
        } catch (e: ApiException.RateLimited) {
            printError("generate.rig", "RATE_LIMITED", "API rate limit exceeded")
        } catch (e: ApiException.Timeout) {
            printError("generate.rig", "TIMEOUT", e.message ?: "Request timed out")
        } catch (e: ApiException) {
            printError("generate.rig", "GENERATION_FAILED", e.message ?: "Rigging failed")
        } finally {
            client.close()
        }
    }
}

/** Saves downloaded Meshy files as approved MODEL_3D assets and returns their paths. */
internal class MeshyAssetSaver(private val ctx: CliContext, private var ws: Workspace, private val folder: String) {

    fun save(bytes: ByteArray, description: String): String {
        val (assetId, fileName) = generateAssetIdentifiers(description, "glb")
        val asset = GeneratedAsset(
            id = assetId,
            type = AssetType.MODEL_3D,
            fileName = fileName,
            filePath = "",
            format = "glb",
            description = description,
            sizeBytes = bytes.size.toLong(),
            status = AssetDecision.APPROVED,
            folder = folder
        )
        val (path, updated) = ctx.workspaceManager.saveAsset(ws, asset, bytes)
        ws = updated
        return path.toString()
    }
}
