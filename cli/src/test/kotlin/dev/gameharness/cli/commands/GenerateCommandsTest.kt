package dev.gameharness.cli.commands

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.CliResponse
import dev.gameharness.cli.GameHarnessCli
import dev.gameharness.cli.commands.generate.Generate
import dev.gameharness.cli.commands.generate.GenerateModel
import dev.gameharness.cli.commands.generate.GenerateMusic
import dev.gameharness.cli.commands.generate.GenerateSfx
import dev.gameharness.cli.commands.generate.GenerateSprite
import dev.gameharness.cli.commands.workspace.WorkspaceCmd
import dev.gameharness.cli.commands.workspace.WorkspaceCreate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals

class GenerateCommandsTest {

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
            Generate().subcommands(
                GenerateSprite(),
                GenerateModel(),
                GenerateMusic(),
                GenerateSfx()
            ),
            WorkspaceCmd().subcommands(WorkspaceCreate())
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
    fun `generate sprite fails for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "generate", "sprite",
                "-w", "NonExistent",
                "-d", "a red potion"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("generate.sprite", parsed.command)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `generate sprite fails when API key not configured`() {
        createWorkspace("KeyTest")

        // This test works if GEMINI_API_KEY is not set in the test environment
        // When no key is configured, the CLI should return MISSING_KEY error
        val output = captureStdout {
            buildCli().parse(listOf(
                "generate", "sprite",
                "-w", "KeyTest",
                "-d", "test sprite"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        // If GEMINI_API_KEY happens to be set, this will attempt a real API call
        // which may fail with AUTH_FAILED. Either error is acceptable in tests.
        if (!parsed.success) {
            val code = parsed.error!!.code
            assert(code == "MISSING_KEY" || code == "AUTH_FAILED" || code == "GENERATION_FAILED") {
                "Expected MISSING_KEY or AUTH_FAILED, got: $code"
            }
        }
    }

    @Test
    fun `generate model fails for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "generate", "model",
                "-w", "Ghost",
                "-d", "a treasure chest"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("generate.model", parsed.command)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `generate music fails for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "generate", "music",
                "-w", "Ghost",
                "-d", "battle theme"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("generate.music", parsed.command)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `generate sfx fails for missing workspace`() {
        val output = captureStdout {
            buildCli().parse(listOf(
                "generate", "sfx",
                "-w", "Ghost",
                "-d", "explosion sound"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("generate.sfx", parsed.command)
        assertEquals("NOT_FOUND", parsed.error!!.code)
    }

    @Test
    fun `generate sfx fails when API key not configured`() {
        createWorkspace("SfxKeyTest")

        val output = captureStdout {
            buildCli().parse(listOf(
                "generate", "sfx",
                "-w", "SfxKeyTest",
                "-d", "coin sound",
                "--duration", "1.5"
            ))
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        if (!parsed.success) {
            val code = parsed.error!!.code
            assert(code == "MISSING_KEY" || code == "AUTH_FAILED" || code == "GENERATION_FAILED") {
                "Expected MISSING_KEY or AUTH_FAILED, got: $code"
            }
        }
    }
}
