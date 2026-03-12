package dev.gameharness.cli

import dev.gameharness.core.config.AppConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ClientFactoryTest {

    private val progressReporter = ProgressReporter()

    @Test
    fun `createSpriteClient throws when gemini key missing`() {
        val config = AppConfig(openRouterApiKey = "", geminiApiKey = null)
        val factory = ClientFactory(config, progressReporter)

        val ex = assertFailsWith<MissingKeyException> {
            factory.createSpriteClient()
        }
        assertEquals("GEMINI_API_KEY", ex.keyName)
        assertEquals("sprite", ex.assetType)
    }

    @Test
    fun `createModelClient throws when meshy key missing`() {
        val config = AppConfig(openRouterApiKey = "", meshyApiKey = null)
        val factory = ClientFactory(config, progressReporter)

        val ex = assertFailsWith<MissingKeyException> {
            factory.createModelClient()
        }
        assertEquals("MESHY_API_KEY", ex.keyName)
    }

    @Test
    fun `createMusicClient throws when suno key missing`() {
        val config = AppConfig(openRouterApiKey = "", sunoApiKey = null)
        val factory = ClientFactory(config, progressReporter)

        val ex = assertFailsWith<MissingKeyException> {
            factory.createMusicClient()
        }
        assertEquals("SUNO_API_KEY", ex.keyName)
    }

    @Test
    fun `createSfxClient throws when elevenlabs key missing`() {
        val config = AppConfig(openRouterApiKey = "", elevenLabsApiKey = null)
        val factory = ClientFactory(config, progressReporter)

        val ex = assertFailsWith<MissingKeyException> {
            factory.createSfxClient()
        }
        assertEquals("ELEVENLABS_API_KEY", ex.keyName)
    }

    @Test
    fun `createSpriteClient returns client when key present`() {
        val config = AppConfig(openRouterApiKey = "", geminiApiKey = "test-key")
        val factory = ClientFactory(config, progressReporter)

        val client = factory.createSpriteClient()
        assertNotNull(client)
        client.close()
    }

    @Test
    fun `createModelClient returns client when key present`() {
        val config = AppConfig(openRouterApiKey = "", meshyApiKey = "test-key")
        val factory = ClientFactory(config, progressReporter)

        val client = factory.createModelClient()
        assertNotNull(client)
        client.close()
    }

    @Test
    fun `createMusicClient returns client when key present`() {
        val config = AppConfig(openRouterApiKey = "", sunoApiKey = "test-key")
        val factory = ClientFactory(config, progressReporter)

        val client = factory.createMusicClient()
        assertNotNull(client)
        client.close()
    }

    @Test
    fun `createSfxClient returns client when key present`() {
        val config = AppConfig(openRouterApiKey = "", elevenLabsApiKey = "test-key")
        val factory = ClientFactory(config, progressReporter)

        val client = factory.createSfxClient()
        assertNotNull(client)
        client.close()
    }
}
