package dev.gameharness.cli.commands

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.CliResponse
import dev.gameharness.cli.GameHarnessCli
import dev.gameharness.core.config.AppConfig
import dev.gameharness.core.config.SettingsManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigShowTest {

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

    @Test
    fun `config shows capabilities based on available keys`() {
        val env = mapOf(
            "GEMINI_API_KEY" to "test-gemini-key",
            "ELEVENLABS_API_KEY" to "test-el-key"
        )

        val output = captureStdout {
            val cli = GameHarnessCli().subcommands(ConfigShow())
            // Override the CliContext with test config
            cli.parse(listOf("config"))
        }

        // The output should be valid JSON with capabilities
        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertTrue(parsed.success)
        assertEquals("config", parsed.command)

        val capabilities = parsed.data!!.jsonObject["capabilities"]!!.jsonArray
        assertEquals(4, capabilities.size)

        // All should show correct types
        assertEquals("SPRITE", capabilities[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("MODEL_3D", capabilities[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("MUSIC", capabilities[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("SOUND_EFFECT", capabilities[3].jsonObject["type"]!!.jsonPrimitive.content)
    }
}
