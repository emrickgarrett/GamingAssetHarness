package dev.gameharness.core.config

/**
 * Runtime configuration holding all API keys and generation preferences.
 *
 * OpenRouter is required for the LLM agent brain; all other API keys are optional
 * and control which asset generation capabilities are available.
 *
 * @property openRouterApiKey Required key for the OpenRouter LLM service.
 * @property geminiApiKey Optional key enabling 2D sprite generation via NanoBanana (Gemini).
 * @property meshyApiKey Optional key enabling 3D model generation via Meshy.
 * @property sunoApiKey Optional key enabling music generation via Suno.
 * @property elevenLabsApiKey Optional key enabling sound effect generation via ElevenLabs.
 * @property nanoBananaModel The Gemini model variant to use for sprite generation.
 * @property maxConcurrentGenerations Maximum number of asset generations that can run in parallel.
 * @property defaultImageStyle Default art style hint passed to the sprite generator.
 */
data class AppConfig(
    val openRouterApiKey: String,
    val geminiApiKey: String? = null,
    val meshyApiKey: String? = null,
    val sunoApiKey: String? = null,
    val elevenLabsApiKey: String? = null,
    val nanoBananaModel: String = "gemini-2.5-flash-image",
    val maxConcurrentGenerations: Int = 2,
    val defaultImageStyle: String = "16bit"
) {
    /** Returns a string representation with all API keys masked to prevent accidental log exposure. */
    override fun toString(): String =
        "AppConfig(" +
            "openRouterApiKey=${maskApiKey(openRouterApiKey)}, " +
            "geminiApiKey=${maskApiKey(geminiApiKey)}, " +
            "meshyApiKey=${maskApiKey(meshyApiKey)}, " +
            "sunoApiKey=${maskApiKey(sunoApiKey)}, " +
            "elevenLabsApiKey=${maskApiKey(elevenLabsApiKey)}, " +
            "nanoBananaModel=$nanoBananaModel, " +
            "maxConcurrentGenerations=$maxConcurrentGenerations, " +
            "defaultImageStyle=$defaultImageStyle)"

    /** Returns a human-readable list of capabilities based on which keys are configured. */
    fun availableCapabilities(): List<String> = buildList {
        add("Chat (OpenRouter)") // always available
        if (!geminiApiKey.isNullOrBlank()) add("2D Sprites (NanoBanana)")
        if (!meshyApiKey.isNullOrBlank()) add("3D Models (Meshy)")
        if (!sunoApiKey.isNullOrBlank()) add("Music (Suno)")
        if (!elevenLabsApiKey.isNullOrBlank()) add("Sound Effects (ElevenLabs)")
    }

    companion object {
        /**
         * Loads config from environment variables only.
         * Only OpenRouter key is required; others are optional.
         */
        fun fromEnvironment(envProvider: (String) -> String? = System::getenv): AppConfig {
            val openRouterKey = envProvider("OPENROUTER_API_KEY")?.trim()
            if (openRouterKey.isNullOrBlank()) {
                throw IllegalStateException(
                    "Missing required environment variable: OPENROUTER_API_KEY"
                )
            }

            fun optionalKey(envKey: String): String? =
                envProvider(envKey)?.trim()?.takeIf { it.isNotBlank() }

            return AppConfig(
                openRouterApiKey = openRouterKey,
                geminiApiKey = optionalKey("GEMINI_API_KEY"),
                meshyApiKey = optionalKey("MESHY_API_KEY"),
                sunoApiKey = optionalKey("SUNO_API_KEY"),
                elevenLabsApiKey = optionalKey("ELEVENLABS_API_KEY")
            )
        }

        /**
         * Loads config from saved settings file, with env vars as overrides.
         * Returns null if no valid config can be assembled (i.e. OpenRouter key missing).
         */
        fun fromSettings(
            settingsManager: SettingsManager,
            envProvider: (String) -> String? = System::getenv
        ): AppConfig? {
            val saved = settingsManager.load()

            // For each key: use env var if present, else use saved value (always trimmed)
            fun resolve(envKey: String, savedValue: String?): String? {
                val envVal = envProvider(envKey)?.trim()
                if (!envVal.isNullOrBlank()) return envVal
                return savedValue?.trim()?.takeIf { it.isNotBlank() }
            }

            val openRouterKey = resolve("OPENROUTER_API_KEY", saved?.openRouterApiKey)
            if (openRouterKey.isNullOrBlank()) return null

            return AppConfig(
                openRouterApiKey = openRouterKey,
                geminiApiKey = resolve("GEMINI_API_KEY", saved?.geminiApiKey),
                meshyApiKey = resolve("MESHY_API_KEY", saved?.meshyApiKey),
                sunoApiKey = resolve("SUNO_API_KEY", saved?.sunoApiKey),
                elevenLabsApiKey = resolve("ELEVENLABS_API_KEY", saved?.elevenLabsApiKey),
                nanoBananaModel = saved?.nanoBananaModel ?: "gemini-2.5-flash-image"
            )
        }

        /**
         * Loads config for CLI usage where OpenRouter is not required.
         * The CLI calls API clients directly without the LLM agent,
         * so only the individual generation API keys matter.
         */
        fun forCli(
            settingsManager: SettingsManager,
            envProvider: (String) -> String? = System::getenv
        ): AppConfig {
            val saved = settingsManager.load()

            fun resolve(envKey: String, savedValue: String?): String? {
                val envVal = envProvider(envKey)?.trim()
                if (!envVal.isNullOrBlank()) return envVal
                return savedValue?.trim()?.takeIf { it.isNotBlank() }
            }

            return AppConfig(
                openRouterApiKey = resolve("OPENROUTER_API_KEY", saved?.openRouterApiKey) ?: "",
                geminiApiKey = resolve("GEMINI_API_KEY", saved?.geminiApiKey),
                meshyApiKey = resolve("MESHY_API_KEY", saved?.meshyApiKey),
                sunoApiKey = resolve("SUNO_API_KEY", saved?.sunoApiKey),
                elevenLabsApiKey = resolve("ELEVENLABS_API_KEY", saved?.elevenLabsApiKey),
                nanoBananaModel = saved?.nanoBananaModel ?: "gemini-2.5-flash-image"
            )
        }
    }
}
