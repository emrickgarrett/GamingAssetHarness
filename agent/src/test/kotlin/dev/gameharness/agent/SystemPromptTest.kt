package dev.gameharness.agent

import dev.gameharness.core.config.AppConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptTest {

    private val fullConfig = AppConfig(
        openRouterApiKey = "or-key",
        geminiApiKey = "gem-key",
        meshyApiKey = "mesh-key",
        sunoApiKey = "suno-key",
        elevenLabsApiKey = "el-key",
    )

    private val minimalConfig = AppConfig(
        openRouterApiKey = "or-key",
    )

    private val partialConfig = AppConfig(
        openRouterApiKey = "or-key",
        geminiApiKey = "gem-key",
        sunoApiKey = "suno-key",
    )

    // --- Full-config prompt tests (replaces legacy constant tests) ---

    @Test
    fun `full config prompt mentions all available tools`() {
        val prompt = buildSystemPrompt(fullConfig)
        assertTrue(prompt.contains("generate_sprite"))
        assertTrue(prompt.contains("generate_3d_model"))
        assertTrue(prompt.contains("generate_music"))
        assertTrue(prompt.contains("generate_sound_effect"))
        assertTrue(prompt.contains("present_asset_to_user"))
    }

    @Test
    fun `full config prompt describes workflow`() {
        val prompt = buildSystemPrompt(fullConfig)
        assertTrue(prompt.contains("APPROVED"))
        assertTrue(prompt.contains("DENIED"))
    }

    @Test
    fun `full config prompt is not blank`() {
        val prompt = buildSystemPrompt(fullConfig)
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.length > 100)
    }

    // --- Dynamic buildSystemPrompt tests ---

    @Test
    fun `buildSystemPrompt with all keys includes all tools`() {
        val prompt = buildSystemPrompt(fullConfig)
        assertTrue(prompt.contains("generate_sprite"))
        assertTrue(prompt.contains("generate_3d_model"))
        assertTrue(prompt.contains("generate_music"))
        assertTrue(prompt.contains("generate_sound_effect"))
        assertTrue(prompt.contains("present_asset_to_user"))
    }

    @Test
    fun `buildSystemPrompt with no optional keys only includes present_asset`() {
        val prompt = buildSystemPrompt(minimalConfig)
        assertFalse(prompt.contains("generate_sprite"))
        assertFalse(prompt.contains("generate_3d_model"))
        assertFalse(prompt.contains("generate_music"))
        assertFalse(prompt.contains("generate_sound_effect"))
        assertTrue(prompt.contains("present_asset_to_user"))
    }

    @Test
    fun `buildSystemPrompt with partial keys includes only configured tools`() {
        val prompt = buildSystemPrompt(partialConfig)
        assertTrue(prompt.contains("generate_sprite"))
        assertFalse(prompt.contains("generate_3d_model"))
        assertTrue(prompt.contains("generate_music"))
        assertFalse(prompt.contains("generate_sound_effect"))
        assertTrue(prompt.contains("present_asset_to_user"))
    }

    @Test
    fun `buildSystemPrompt always describes workflow`() {
        val prompt = buildSystemPrompt(minimalConfig)
        assertTrue(prompt.contains("APPROVED"))
        assertTrue(prompt.contains("DENIED"))
    }

    @Test
    fun `buildSystemPrompt mentions settings for missing capabilities`() {
        val prompt = buildSystemPrompt(minimalConfig)
        assertTrue(prompt.contains("Settings"))
    }
}
