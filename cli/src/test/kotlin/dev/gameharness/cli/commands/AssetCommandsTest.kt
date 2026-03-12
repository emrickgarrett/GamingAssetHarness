package dev.gameharness.cli.commands

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.CliResponse
import dev.gameharness.cli.GameHarnessCli
import dev.gameharness.cli.commands.asset.AssetCmd
import dev.gameharness.cli.commands.asset.AssetList
import dev.gameharness.cli.commands.workspace.WorkspaceCmd
import dev.gameharness.cli.commands.workspace.WorkspaceCreate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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

    private fun buildCli(): GameHarnessCli {
        return GameHarnessCli().subcommands(
            WorkspaceCmd().subcommands(WorkspaceCreate()),
            AssetCmd().subcommands(AssetList())
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
}
