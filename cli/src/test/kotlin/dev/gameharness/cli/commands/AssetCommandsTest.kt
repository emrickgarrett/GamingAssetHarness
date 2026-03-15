package dev.gameharness.cli.commands

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.CliResponse
import dev.gameharness.cli.GameHarnessCli
import dev.gameharness.cli.commands.asset.AssetCmd
import dev.gameharness.cli.commands.asset.AssetList
import dev.gameharness.cli.commands.asset.AssetRevise
import dev.gameharness.cli.commands.asset.AssetTrim
import dev.gameharness.cli.commands.workspace.WorkspaceCmd
import dev.gameharness.cli.commands.workspace.WorkspaceCreate
import dev.gameharness.core.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetCommandsTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { ignoreUnknownKeys = true }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return baos.toString().trim()
    }

    private val wsJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private fun buildCli(): GameHarnessCli {
        return GameHarnessCli().subcommands(
            WorkspaceCmd().subcommands(WorkspaceCreate()),
            AssetCmd().subcommands(AssetList(), AssetRevise(), AssetTrim())
        )
    }

    private fun createWorkspace(name: String): Path {
        val wsDir = tempDir.resolve(name.replace(" ", "_"))
        captureStdout {
            buildCli().parse(listOf(
                "workspace", "create",
                "-n", name,
                "-p", wsDir.toAbsolutePath().toString()
            ))
        }
        return wsDir
    }

    @Test
    fun `asset list returns empty for new workspace`() {
        createWorkspace("EmptyProject")

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "list",
                "-w", "EmptyProject"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertTrue(parsed.success)
        assertEquals("asset.list", parsed.command)
        assertEquals("EmptyProject", parsed.data!!.jsonObject["workspace"]!!.jsonPrimitive.content)
        assertEquals(0, parsed.data!!.jsonObject["totalCount"]!!.jsonPrimitive.int)
        assertTrue(parsed.data!!.jsonObject["assets"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `asset list returns error for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "list",
                "-w", "DoesNotExist"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `asset list with invalid type filter returns error`() {
        createWorkspace("FilterTest")

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "list",
                "-w", "FilterTest",
                "--type", "INVALID_TYPE"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("INVALID_ARGS", parsed.error!!.code)
    }

    // ── asset revise ─────────────────────────────────────────────────

    /** Writes a workspace.json with the given assets into an existing workspace directory. */
    private fun writeWorkspaceWithAssets(wsDir: Path, wsName: String, assets: List<GeneratedAsset>) {
        val workspace = Workspace(
            name = wsName,
            directoryPath = wsDir.toAbsolutePath().toString(),
            assets = assets
        )
        Files.writeString(wsDir.resolve("workspace.json"), wsJson.encodeToString(workspace))
    }

    @Test
    fun `asset revise returns error for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "revise",
                "-w", "DoesNotExist",
                "-a", "sprite.png",
                "-d", "make it brighter"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("asset.revise", parsed.command)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `asset revise returns error when asset not found`() {
        val wsDir = createWorkspace("ReviseEmpty")

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "revise",
                "-w", "ReviseEmpty",
                "-a", "nonexistent.png",
                "-d", "make it brighter"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("ASSET_NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `asset revise returns error for non-sprite asset`() {
        val wsDir = createWorkspace("ReviseModel")
        val modelAsset = GeneratedAsset(
            id = "model-001",
            type = AssetType.MODEL_3D,
            fileName = "chest.glb",
            filePath = wsDir.resolve("assets/models/chest.glb").toAbsolutePath().toString(),
            format = "glb",
            description = "a treasure chest"
        )
        writeWorkspaceWithAssets(wsDir, "ReviseModel", listOf(modelAsset))

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "revise",
                "-w", "ReviseModel",
                "-a", "chest.glb",
                "-d", "make it bigger"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("UNSUPPORTED_TYPE", parsed.error!!.code)
        assertTrue(parsed.error!!.message.contains("Only sprites"))
    }

    @Test
    fun `asset revise returns error for ambiguous match`() {
        val wsDir = createWorkspace("ReviseAmbig")
        val sprite1 = GeneratedAsset(
            id = "s1", type = AssetType.SPRITE, fileName = "sword_red.png",
            filePath = wsDir.resolve("assets/sprites/sword_red.png").toAbsolutePath().toString(),
            format = "png", description = "red sword"
        )
        val sprite2 = GeneratedAsset(
            id = "s2", type = AssetType.SPRITE, fileName = "sword_blue.png",
            filePath = wsDir.resolve("assets/sprites/sword_blue.png").toAbsolutePath().toString(),
            format = "png", description = "blue sword"
        )
        writeWorkspaceWithAssets(wsDir, "ReviseAmbig", listOf(sprite1, sprite2))

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "revise",
                "-w", "ReviseAmbig",
                "-a", "sword",
                "-d", "add glow effect"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("AMBIGUOUS_MATCH", parsed.error!!.code)
        assertTrue(parsed.error!!.message.contains("sword_red.png"))
        assertTrue(parsed.error!!.message.contains("sword_blue.png"))
    }

    @Test
    fun `asset revise returns error when original file missing from disk`() {
        val wsDir = createWorkspace("ReviseMissing")
        val ghostAsset = GeneratedAsset(
            id = "ghost-001", type = AssetType.SPRITE, fileName = "deleted_sprite.png",
            filePath = wsDir.resolve("assets/sprites/deleted_sprite.png").toAbsolutePath().toString(),
            format = "png", description = "a sprite that was deleted"
        )
        writeWorkspaceWithAssets(wsDir, "ReviseMissing", listOf(ghostAsset))

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "revise",
                "-w", "ReviseMissing",
                "-a", "deleted_sprite.png",
                "-d", "make it brighter"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("FILE_NOT_FOUND", parsed.error!!.code)
    }

    // ── asset trim ──────────────────────────────────────────────────

    @Test
    fun `asset trim returns error for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "trim",
                "-w", "DoesNotExist",
                "-a", "sprite.png"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("asset.trim", parsed.command)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `asset trim returns error when asset not found`() {
        createWorkspace("TrimEmpty")

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "trim",
                "-w", "TrimEmpty",
                "-a", "nonexistent.png"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("ASSET_NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `asset trim returns error for non-sprite asset`() {
        val wsDir = createWorkspace("TrimModel")
        val modelAsset = GeneratedAsset(
            id = "model-trim-001",
            type = AssetType.MODEL_3D,
            fileName = "chest.glb",
            filePath = wsDir.resolve("assets/models/chest.glb").toAbsolutePath().toString(),
            format = "glb",
            description = "a treasure chest"
        )
        writeWorkspaceWithAssets(wsDir, "TrimModel", listOf(modelAsset))

        val output = captureStdout {
            buildCli().parse(listOf(
                "asset", "trim",
                "-w", "TrimModel",
                "-a", "chest.glb"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("UNSUPPORTED_TYPE", parsed.error!!.code)
        assertTrue(parsed.error!!.message.contains("Only sprites"))
    }
}
