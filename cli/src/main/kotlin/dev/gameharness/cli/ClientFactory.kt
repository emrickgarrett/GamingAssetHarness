package dev.gameharness.cli

import dev.gameharness.api.gemini.GeminiClient
import dev.gameharness.api.meshy.MeshyClient
import dev.gameharness.api.suno.SunoClient
import dev.gameharness.api.elevenlabs.ElevenLabsClient
import dev.gameharness.core.config.AppConfig

class MissingKeyException(val keyName: String, val assetType: String) :
    RuntimeException("$keyName is not configured. Cannot generate $assetType assets.")

class ClientFactory(private val config: AppConfig, private val progressReporter: ProgressReporter) {

    fun createSpriteClient(): GeminiClient {
        val key = config.geminiApiKey
            ?: throw MissingKeyException("GEMINI_API_KEY", "sprite")
        return GeminiClient(key, config.nanoBananaModel)
    }

    fun createModelClient(): MeshyClient {
        val key = config.meshyApiKey
            ?: throw MissingKeyException("MESHY_API_KEY", "3D model")
        return MeshyClient(key).also { it.onProgress = progressReporter.toCallback() }
    }

    fun createMusicClient(): SunoClient {
        val key = config.sunoApiKey
            ?: throw MissingKeyException("SUNO_API_KEY", "music")
        return SunoClient(key).also { it.onProgress = progressReporter.toCallback() }
    }

    fun createSfxClient(): ElevenLabsClient {
        val key = config.elevenLabsApiKey
            ?: throw MissingKeyException("ELEVENLABS_API_KEY", "sound effect")
        return ElevenLabsClient(key)
    }
}
