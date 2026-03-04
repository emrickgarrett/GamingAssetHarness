package dev.gameharness.core.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {

    private val fullEnv = mapOf(
        "OPENROUTER_API_KEY" to "or-test-key",
        "GEMINI_API_KEY" to "gem-test-key",
        "MESHY_API_KEY" to "meshy-test-key",
        "SUNO_API_KEY" to "suno-test-key",
        "ELEVENLABS_API_KEY" to "el-test-key"
    )

    @Test
    fun `loads all keys from environment when all provided`() {
        val config = AppConfig.fromEnvironment { fullEnv[it] }

        assertEquals("or-test-key", config.openRouterApiKey)
        assertEquals("gem-test-key", config.geminiApiKey)
        assertEquals("meshy-test-key", config.meshyApiKey)
        assertEquals("suno-test-key", config.sunoApiKey)
        assertEquals("el-test-key", config.elevenLabsApiKey)
    }

    @Test
    fun `throws when OpenRouter key missing`() {
        val exception = assertThrows<IllegalStateException> {
            AppConfig.fromEnvironment { null }
        }
        assertTrue(exception.message!!.contains("OPENROUTER_API_KEY"))
    }

    @Test
    fun `succeeds with only OpenRouter key`() {
        val minimalEnv = mapOf("OPENROUTER_API_KEY" to "or-key")
        val config = AppConfig.fromEnvironment { minimalEnv[it] }

        assertEquals("or-key", config.openRouterApiKey)
        assertNull(config.geminiApiKey)
        assertNull(config.meshyApiKey)
        assertNull(config.sunoApiKey)
        assertNull(config.elevenLabsApiKey)
    }

    @Test
    fun `optional keys are null when not in environment`() {
        val onlyRequired = mapOf("OPENROUTER_API_KEY" to "or-key")
        val config = AppConfig.fromEnvironment { onlyRequired[it] }

        assertNull(config.geminiApiKey)
        assertNull(config.meshyApiKey)
        assertNull(config.sunoApiKey)
        assertNull(config.elevenLabsApiKey)
    }

    @Test
    fun `availableCapabilities with all keys`() {
        val config = AppConfig.fromEnvironment { fullEnv[it] }
        val caps = config.availableCapabilities()
        assertEquals(5, caps.size)
        assertTrue(caps.any { it.contains("Chat") })
        assertTrue(caps.any { it.contains("Sprites") })
        assertTrue(caps.any { it.contains("3D Models") })
        assertTrue(caps.any { it.contains("Music") })
        assertTrue(caps.any { it.contains("Sound Effects") })
    }

    @Test
    fun `availableCapabilities with only OpenRouter`() {
        val config = AppConfig.fromEnvironment { mapOf("OPENROUTER_API_KEY" to "or")[it] }
        val caps = config.availableCapabilities()
        assertEquals(1, caps.size)
        assertTrue(caps[0].contains("Chat"))
    }

    @Test
    fun `availableCapabilities with partial keys`() {
        val partial = mapOf(
            "OPENROUTER_API_KEY" to "or",
            "GEMINI_API_KEY" to "gem",
            "SUNO_API_KEY" to "suno"
        )
        val config = AppConfig.fromEnvironment { partial[it] }
        val caps = config.availableCapabilities()
        assertEquals(3, caps.size)
        assertTrue(caps.any { it.contains("Sprites") })
        assertTrue(caps.any { it.contains("Music") })
    }

    @Test
    fun `nanoBananaModel defaults when not in saved settings`() {
        val config = AppConfig.fromEnvironment { fullEnv[it] }
        assertEquals("gemini-2.5-flash-image", config.nanoBananaModel)
    }

    @Test
    fun `nanoBananaModel flows through fromSettings`(@TempDir tempDir: Path) {
        val sm = SettingsManager(tempDir)
        sm.save(SavedSettings(
            openRouterApiKey = "or-key",
            geminiApiKey = "gem-key",
            nanoBananaModel = "gemini-3.1-flash-image-preview"
        ))

        val config = AppConfig.fromSettings(sm) { null }

        assertNotNull(config)
        assertEquals("gemini-3.1-flash-image-preview", config.nanoBananaModel)
    }
}
