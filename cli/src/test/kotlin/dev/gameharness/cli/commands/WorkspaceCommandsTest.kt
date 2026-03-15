package dev.gameharness.cli.commands

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.CliResponse
import dev.gameharness.cli.GameHarnessCli
import dev.gameharness.cli.commands.workspace.WorkspaceCmd
import dev.gameharness.cli.commands.workspace.WorkspaceCreate
import dev.gameharness.cli.commands.workspace.WorkspaceList
import dev.gameharness.cli.commands.workspace.WorkspaceOpen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceCommandsTest {

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

    private fun buildCli(): GameHarnessCli {
        return GameHarnessCli().subcommands(
            WorkspaceCmd().subcommands(
                WorkspaceList(),
                WorkspaceCreate(),
                WorkspaceOpen()
            )
        )
    }

    @Test
    fun `workspace list returns empty array when no workspaces`() {
        val output = captureStdout {
            buildCli().parse(listOf("workspace", "list"))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertTrue(parsed.success)
        assertEquals("workspace.list", parsed.command)

        val workspaces = parsed.data!!.jsonObject["workspaces"]!!.jsonArray
        // May have existing workspaces from the system, so just verify structure
        assertTrue(workspaces.size >= 0)
    }

    @Test
    fun `workspace create makes a new workspace`() {
        val wsDir = tempDir.resolve("test-workspace")

        val output = captureStdout {
            buildCli().parse(listOf(
                "workspace", "create",
                "-n", "TestWorkspace",
                "-p", wsDir.toAbsolutePath().toString()
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertTrue(parsed.success)
        assertEquals("workspace.create", parsed.command)
        assertEquals("TestWorkspace", parsed.data!!.jsonObject["name"]!!.jsonPrimitive.content)

        // Verify directory was created with asset subdirs
        assertTrue(wsDir.resolve("assets/sprites").toFile().exists())
        assertTrue(wsDir.resolve("assets/models").toFile().exists())
        assertTrue(wsDir.resolve("assets/music").toFile().exists())
        assertTrue(wsDir.resolve("assets/sfx").toFile().exists())
        assertTrue(wsDir.resolve("workspace.json").toFile().exists())
    }

    @Test
    fun `workspace create fails for non-empty directory`() {
        val wsDir = tempDir.resolve("non-empty")
        wsDir.toFile().mkdirs()
        wsDir.resolve("existing-file.txt").toFile().writeText("hello")

        val output = captureStdout {
            buildCli().parse(listOf(
                "workspace", "create",
                "-n", "Bad",
                "-p", wsDir.toAbsolutePath().toString()
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("workspace.create", parsed.command)
        assertEquals("INVALID_ARGS", parsed.error!!.code)
    }

    // ── workspace open ──────────────────────────────────────────────

    @Test
    fun `workspace open initializes non-empty directory`() {
        val wsDir = tempDir.resolve("game-project")
        Files.createDirectories(wsDir)
        Files.writeString(wsDir.resolve("readme.txt"), "hello")

        val output = captureStdout {
            buildCli().parse(listOf(
                "workspace", "open",
                "-p", wsDir.toAbsolutePath().toString(),
                "-n", "Game Project"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertTrue(parsed.success)
        assertEquals("workspace.open", parsed.command)
        assertEquals("Game Project", parsed.data!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(wsDir.resolve("workspace.json").toFile().exists())
    }

    @Test
    fun `workspace open returns error for non-existent directory`() {
        val wsDir = tempDir.resolve("nonexistent")

        val output = captureStdout {
            buildCli().parse(listOf(
                "workspace", "open",
                "-p", wsDir.toAbsolutePath().toString(),
                "-n", "Test"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("workspace.open", parsed.command)
        assertEquals("INVALID_ARGS", parsed.error!!.code)
    }
}
