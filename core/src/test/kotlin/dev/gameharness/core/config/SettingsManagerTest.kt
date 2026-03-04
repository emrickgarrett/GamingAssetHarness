package dev.gameharness.core.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SettingsManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var manager: SettingsManager

    @BeforeEach
    fun setup() {
        manager = SettingsManager(settingsDir = tempDir)
    }

    @Test
    fun `exists returns false when no settings file`() {
        assertFalse(manager.exists())
    }

    @Test
    fun `save and load round-trip`() {
        val settings = SavedSettings(
            openRouterApiKey = "or-key",
            geminiApiKey = "gem-key",
            meshyApiKey = "mesh-key",
            sunoApiKey = "suno-key",
            elevenLabsApiKey = "el-key"
        )

        manager.save(settings)
        assertTrue(manager.exists())

        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals(settings, loaded)
    }

    @Test
    fun `load returns null for missing file`() {
        assertNull(manager.load())
    }

    @Test
    fun `load returns null for corrupt file`() {
        val settingsFile = tempDir.resolve("settings.json")
        Files.writeString(settingsFile, "this is not json{{{")
        assertNull(manager.load())
    }

    @Test
    fun `hasRequiredKeys returns true when OpenRouter key present`() {
        val settings = SavedSettings(openRouterApiKey = "a")
        assertTrue(settings.hasRequiredKeys())
    }

    @Test
    fun `hasRequiredKeys returns false when OpenRouter key blank`() {
        assertFalse(SavedSettings().hasRequiredKeys())
    }

    @Test
    fun `hasRequiredKeys returns true even without optional keys`() {
        val settings = SavedSettings(openRouterApiKey = "a")
        assertTrue(settings.hasRequiredKeys())
    }

    @Test
    fun `hasAllKeys returns true when all keys present`() {
        val settings = SavedSettings(
            openRouterApiKey = "a",
            geminiApiKey = "b",
            meshyApiKey = "c",
            sunoApiKey = "d",
            elevenLabsApiKey = "e"
        )
        assertTrue(settings.hasAllKeys())
    }

    @Test
    fun `hasAllKeys returns false when any key blank`() {
        val settings = SavedSettings(
            openRouterApiKey = "a",
            geminiApiKey = "",
            meshyApiKey = "c",
            sunoApiKey = "d",
            elevenLabsApiKey = "e"
        )
        assertFalse(settings.hasAllKeys())
    }

    @Test
    fun `hasAllKeys returns false with defaults`() {
        assertFalse(SavedSettings().hasAllKeys())
    }

    @Test
    fun `save creates directory if missing`() {
        val nestedDir = tempDir.resolve("sub").resolve("dir")
        val nestedManager = SettingsManager(settingsDir = nestedDir)

        nestedManager.save(SavedSettings(
            openRouterApiKey = "key",
            geminiApiKey = "key",
            meshyApiKey = "key",
            sunoApiKey = "key",
            elevenLabsApiKey = "key"
        ))

        assertTrue(nestedManager.exists())
    }

    // ── Encryption tests ────────────────────────────────────────────────

    @Test
    fun `saved file is not plaintext`() {
        val settings = SavedSettings(
            openRouterApiKey = "sk-or-v1-secret123",
            geminiApiKey = "AIza-secret-gem"
        )
        manager.save(settings)

        val rawContent = Files.readString(tempDir.resolve("settings.json"))
        assertFalse(rawContent.contains("sk-or-v1-secret123"), "Raw file should not contain plaintext API key")
        assertFalse(rawContent.contains("AIza-secret-gem"), "Raw file should not contain plaintext API key")
        assertFalse(rawContent.contains("openRouterApiKey"), "Raw file should not contain field names")
    }

    @Test
    fun `saved file has EncryptedPayload structure`() {
        manager.save(SavedSettings(openRouterApiKey = "test-key"))

        val rawContent = Files.readString(tempDir.resolve("settings.json"))
        val obj = Json.parseToJsonElement(rawContent).jsonObject
        assertTrue("version" in obj, "Encrypted file must have 'version' key")
        assertTrue("iv" in obj, "Encrypted file must have 'iv' key")
        assertTrue("data" in obj, "Encrypted file must have 'data' key")
    }

    @Test
    fun `plaintext migration on load`() {
        // Write a plaintext settings file directly (simulating pre-encryption format)
        val plaintextJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val settings = SavedSettings(
            openRouterApiKey = "migrated-or-key",
            geminiApiKey = "migrated-gem-key"
        )
        val settingsFile = tempDir.resolve("settings.json")
        Files.writeString(settingsFile, plaintextJson.encodeToString(settings))

        // Load should detect plaintext and parse successfully
        val loaded = manager.load()
        assertNotNull(loaded)
        assertEquals("migrated-or-key", loaded.openRouterApiKey)
        assertEquals("migrated-gem-key", loaded.geminiApiKey)
    }

    @Test
    fun `plaintext migration auto-encrypts the file`() {
        // Write a plaintext settings file
        val plaintextJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val settings = SavedSettings(openRouterApiKey = "migrate-me")
        val settingsFile = tempDir.resolve("settings.json")
        Files.writeString(settingsFile, plaintextJson.encodeToString(settings))

        // Trigger migration
        manager.load()

        // File should now be in encrypted format
        val rawContent = Files.readString(settingsFile)
        assertFalse(rawContent.contains("migrate-me"), "File should no longer contain plaintext key")
        val obj = Json.parseToJsonElement(rawContent).jsonObject
        assertTrue("iv" in obj, "Migrated file should be in encrypted format")
    }

    @Test
    fun `missing keystore makes old encrypted settings unreadable`() {
        val settings = SavedSettings(
            openRouterApiKey = "original-key",
            geminiApiKey = "original-gem"
        )
        manager.save(settings)

        // Delete the keystore file (simulates lost master key)
        val keystoreFile = tempDir.resolve(".keystore")
        assertTrue(Files.exists(keystoreFile))
        Files.delete(keystoreFile)

        // Create a new manager (which will generate a new key)
        val newManager = SettingsManager(settingsDir = tempDir)

        // Old settings are undecryptable with the new key
        assertNull(newManager.load())

        // But new settings work fine
        val newSettings = SavedSettings(openRouterApiKey = "new-key")
        newManager.save(newSettings)
        val loaded = newManager.load()
        assertNotNull(loaded)
        assertEquals("new-key", loaded.openRouterApiKey)
    }

    @Test
    fun `keystore file is created after first save`() {
        val keystoreFile = tempDir.resolve(".keystore")
        assertFalse(Files.exists(keystoreFile))

        manager.save(SavedSettings(openRouterApiKey = "trigger"))

        assertTrue(Files.exists(keystoreFile))
    }
}

// ── toString masking tests ──────────────────────────────────────────────

class SavedSettingsToStringTest {

    @Test
    fun `toString masks all API keys`() {
        val settings = SavedSettings(
            openRouterApiKey = "sk-or-v1-abc123def456",
            geminiApiKey = "AIzaSyBcDE_longkey",
            meshyApiKey = "msy_secret_key_value",
            sunoApiKey = "suno_api_key_12345",
            elevenLabsApiKey = "el_key_secret_value"
        )
        val str = settings.toString()

        // Should NOT contain any full key
        assertFalse(str.contains("sk-or-v1-abc123def456"))
        assertFalse(str.contains("AIzaSyBcDE_longkey"))
        assertFalse(str.contains("msy_secret_key_value"))
        assertFalse(str.contains("suno_api_key_12345"))
        assertFalse(str.contains("el_key_secret_value"))

        // Should contain masked prefixes
        assertTrue(str.contains("sk-o****"))
        assertTrue(str.contains("AIza****"))
        assertTrue(str.contains("msy_****"))
        assertTrue(str.contains("suno****"))
        assertTrue(str.contains("el_k****"))

        // Non-key fields should be shown normally
        assertTrue(str.contains("nanoBananaModel="))
        assertTrue(str.contains("darkMode="))
    }

    @Test
    fun `toString shows empty for blank keys`() {
        val settings = SavedSettings()
        val str = settings.toString()

        // All keys default to "" which should show as (empty)
        assertTrue(str.contains("openRouterApiKey=(empty)"))
        assertTrue(str.contains("geminiApiKey=(empty)"))
    }
}

class AppConfigToStringTest {

    @Test
    fun `toString masks all API keys`() {
        val config = AppConfig(
            openRouterApiKey = "sk-or-v1-secret123",
            geminiApiKey = "AIzaSySecret",
            meshyApiKey = "msy_secret",
            sunoApiKey = "suno_secret",
            elevenLabsApiKey = "el_secret"
        )
        val str = config.toString()

        assertFalse(str.contains("sk-or-v1-secret123"))
        assertFalse(str.contains("AIzaSySecret"))
        assertTrue(str.contains("sk-o****"))
        assertTrue(str.contains("AIza****"))

        // Non-key fields displayed normally
        assertTrue(str.contains("maxConcurrentGenerations="))
        assertTrue(str.contains("defaultImageStyle="))
    }

    @Test
    fun `toString masks null optional keys as empty`() {
        val config = AppConfig(openRouterApiKey = "sk-or-v1-key")
        val str = config.toString()

        assertTrue(str.contains("geminiApiKey=(empty)"))
        assertTrue(str.contains("meshyApiKey=(empty)"))
        assertTrue(str.contains("sunoApiKey=(empty)"))
        assertTrue(str.contains("elevenLabsApiKey=(empty)"))
    }
}

class AppConfigFromSettingsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var settingsManager: SettingsManager

    private val fullSettings = SavedSettings(
        openRouterApiKey = "saved-or",
        geminiApiKey = "saved-gem",
        meshyApiKey = "saved-mesh",
        sunoApiKey = "saved-suno",
        elevenLabsApiKey = "saved-el"
    )

    @BeforeEach
    fun setup() {
        settingsManager = SettingsManager(settingsDir = tempDir)
    }

    @Test
    fun `fromSettings returns config when all keys saved`() {
        settingsManager.save(fullSettings)

        val config = AppConfig.fromSettings(settingsManager) { null }
        assertNotNull(config)
        assertEquals("saved-or", config.openRouterApiKey)
        assertEquals("saved-gem", config.geminiApiKey)
    }

    @Test
    fun `fromSettings returns config with only OpenRouter key`() {
        val minimalSettings = SavedSettings(openRouterApiKey = "saved-or")
        settingsManager.save(minimalSettings)

        val config = AppConfig.fromSettings(settingsManager) { null }
        assertNotNull(config)
        assertEquals("saved-or", config.openRouterApiKey)
        assertNull(config.geminiApiKey)
        assertNull(config.meshyApiKey)
        assertNull(config.sunoApiKey)
        assertNull(config.elevenLabsApiKey)
    }

    @Test
    fun `fromSettings returns null when no saved settings`() {
        val config = AppConfig.fromSettings(settingsManager) { null }
        assertNull(config)
    }

    @Test
    fun `fromSettings returns null when OpenRouter key missing`() {
        val noOpenRouter = SavedSettings(
            geminiApiKey = "gem",
            meshyApiKey = "mesh",
            sunoApiKey = "suno",
            elevenLabsApiKey = "el"
        )
        settingsManager.save(noOpenRouter)

        val config = AppConfig.fromSettings(settingsManager) { null }
        assertNull(config)
    }

    @Test
    fun `fromSettings env var overrides saved value`() {
        settingsManager.save(fullSettings)

        val config = AppConfig.fromSettings(settingsManager) { key ->
            if (key == "OPENROUTER_API_KEY") "env-or" else null
        }
        assertNotNull(config)
        assertEquals("env-or", config.openRouterApiKey)
        assertEquals("saved-gem", config.geminiApiKey)
    }

    @Test
    fun `fromSettings env var fills missing saved key`() {
        val partial = fullSettings.copy(geminiApiKey = "")
        settingsManager.save(partial)

        val config = AppConfig.fromSettings(settingsManager) { key ->
            if (key == "GEMINI_API_KEY") "env-gem" else null
        }
        assertNotNull(config)
        assertEquals("env-gem", config.geminiApiKey)
    }

    @Test
    fun `fromSettings optional key stays null when missing from both sources`() {
        val partial = fullSettings.copy(geminiApiKey = "")
        settingsManager.save(partial)

        val config = AppConfig.fromSettings(settingsManager) { null }
        assertNotNull(config) // Still returns config because OpenRouter is present
        assertNull(config.geminiApiKey)
    }

    @Test
    fun `fromSettings env var provides OpenRouter when saved is empty`() {
        // Saved settings exist but OpenRouter is empty
        val noOpenRouter = SavedSettings(geminiApiKey = "saved-gem")
        settingsManager.save(noOpenRouter)

        val config = AppConfig.fromSettings(settingsManager) { key ->
            if (key == "OPENROUTER_API_KEY") "env-or" else null
        }
        assertNotNull(config)
        assertEquals("env-or", config.openRouterApiKey)
        assertEquals("saved-gem", config.geminiApiKey)
    }
}
