package dev.gameharness.cli.commands.sculpt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.assetToJson
import dev.gameharness.cli.printError
import dev.gameharness.cli.printSuccess
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GeneratedAsset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * `harness sculpt bake` — turn the generated Three.js factory into a .glb and
 * register it as a normal workspace asset.
 *
 * This is what makes the pipeline useful beyond Three.js. img2threejs emits
 * TypeScript, which is great for animation and editing but can't be shown in
 * the harness's GLB viewer and can't be loaded by other engines. Baking keeps
 * the factory as the editable master and produces a portable mesh: the asset
 * lands as a normal [AssetType.MODEL_3D], so the existing viewer and every
 * asset command work on it with no special-casing.
 *
 * Provenance (spec, factory, provider) is recorded in generationParams so the
 * asset can always be traced back to the sculpt session that produced it.
 */
class SculptBake : CliktCommand(name = "bake", help = "Bake the Three.js factory to .glb and register it") {

    private val ctx by requireObject<CliContext>()
    private val workspace by option("-w", "--workspace", help = "Workspace name").required()
    private val spec by option("-s", "--spec", help = "Sculpt spec JSON").required()
    private val factory by option("--factory", help = "Generated createXModel.ts").required()
    private val exportName by option("--export", help = "Factory export name (default: auto-detect)")
    private val folder by option("-f", "--folder", help = "Virtual folder within workspace").default("")
    private val description by option("-d", "--description", help = "Asset description")
    private val name by option("-n", "--name", help = "Asset name (default: derived from the spec filename)")

    override fun run() {
        val ws = ctx.workspaceManager.getWorkspace(workspace)
        if (ws == null) {
            printError("sculpt.bake", "NOT_FOUND", "Workspace '$workspace' not found")
            return
        }
        val specFile = File(spec).absoluteFile
        val factoryFile = File(factory).absoluteFile
        for (f in listOf(specFile, factoryFile)) {
            if (!f.isFile) {
                printError("sculpt.bake", "NOT_FOUND", "File not found: ${f.path}")
                return
            }
        }

        val bakeDir = findRepoDir("tools/threejs-bake") { File(it, "bake.mjs").isFile }
        if (bakeDir == null) {
            printError(
                "sculpt.bake",
                "BAKE_MISSING",
                "bake tool not found (looked for tools/threejs-bake/bake.mjs above " +
                    searchRoots().joinToString(" and ") { it.path } + ")"
            )
            return
        }
        if (!File(bakeDir, "node_modules").isDirectory) {
            printError("sculpt.bake", "BAKE_DEPS", "run 'npm install' in ${bakeDir.path} first (needs three + tsx)")
            return
        }

        // `<slug>.spec.json` (what sculpt init lays down) → `<slug>`
        val objectName = name ?: specFile.name.removeSuffix(".json").removeSuffix(".spec")
        val outGlb = File.createTempFile("sculpt-$objectName-", ".glb").apply { deleteOnExit() }

        val cmd = mutableListOf(
            npx(), "tsx", "bake.mjs",
            "--factory", factoryFile.path,
            "--spec", specFile.path,
            "--out", outGlb.path,
        )
        exportName?.let { cmd += listOf("--export", it) }

        val proc = ProcessBuilder(cmd).directory(bakeDir).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        if (!proc.waitFor(BAKE_TIMEOUT_MIN, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            printError("sculpt.bake", "TIMEOUT", "bake timed out after $BAKE_TIMEOUT_MIN min")
            return
        }
        if (proc.exitValue() != 0) {
            printError("sculpt.bake", "BAKE_FAILED", (stderr.ifBlank { stdout }).trim().takeLast(1500))
            return
        }

        // bake.mjs reports a one-line JSON summary on stdout
        val report = runCatching {
            Json.parseToJsonElement(stdout.lines().last { it.trim().startsWith("{") }) as JsonObject
        }.getOrElse {
            printError("sculpt.bake", "BAD_OUTPUT", "could not parse bake output: ${stdout.trim().takeLast(500)}")
            return
        }

        val bytes = outGlb.readBytes()
        val asset = GeneratedAsset(
            id = UUID.randomUUID().toString(),
            type = AssetType.MODEL_3D,
            fileName = "$objectName.glb",
            filePath = "",
            format = "glb",
            description = description ?: "Procedural Three.js model '$objectName' (img2threejs), baked to GLB",
            generationParams = mapOf(
                "provider" to "img2threejs",
                "specPath" to specFile.path,
                "factoryPath" to factoryFile.path,
                "meshes" to (report["meshes"]?.jsonPrimitive?.content ?: "?"),
                "triangles" to (report["triangles"]?.jsonPrimitive?.content ?: "?"),
            ),
            folder = folder,
        )
        val (savedPath, _) = ctx.workspaceManager.saveAsset(ws, asset, bytes)

        val base = assetToJson(
            id = asset.id,
            fileName = asset.fileName,
            filePath = savedPath.toString(),
            type = asset.type.name,
            format = asset.format,
            description = asset.description,
            sizeBytes = bytes.size.toLong(),
            status = asset.status.name,
            folder = asset.folder,
        )
        printSuccess(
            "sculpt.bake",
            JsonObject(
                base.toMutableMap().apply {
                    // provenance back to the sculpt session, plus the mesh census
                    put("provider", JsonPrimitive("img2threejs"))
                    put("specPath", JsonPrimitive(specFile.path))
                    put("factoryPath", JsonPrimitive(factoryFile.path))
                    put("meshes", report["meshes"] ?: JsonPrimitive(0))
                    put("triangles", report["triangles"] ?: JsonPrimitive(0))
                }
            )
        )
    }

    private companion object {
        const val BAKE_TIMEOUT_MIN = 5L

        /** Node 24 refuses to spawn a bare .cmd shim without a shell, so name it explicitly. */
        fun npx(): String =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "npx.cmd" else "npx"
    }
}
