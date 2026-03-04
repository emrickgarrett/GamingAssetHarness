package dev.gameharness.core.config

import dev.gameharness.core.util.ensureDirectoryExists
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Persistent user settings serialized to `~/.gameharness/settings.json`.
 *
 * Stores API keys, the selected NanoBanana model variant, and UI preferences.
 * All key fields default to empty strings so the JSON can be deserialized
 * even when keys are missing from the file.
 */
@Serializable
data class SavedSettings(
    val openRouterApiKey: String = "",
    val geminiApiKey: String = "",
    val meshyApiKey: String = "",
    val sunoApiKey: String = "",
    val elevenLabsApiKey: String = "",
    val nanoBananaModel: String = "gemini-2.5-flash-image",
    val darkMode: Boolean = true
) {
    /** Returns true if the required key (OpenRouter) is present. */
    fun hasRequiredKeys(): Boolean = openRouterApiKey.isNotBlank()

    /** Returns true if every single API key is filled in. */
    fun hasAllKeys(): Boolean =
        openRouterApiKey.isNotBlank() &&
            geminiApiKey.isNotBlank() &&
            meshyApiKey.isNotBlank() &&
            sunoApiKey.isNotBlank() &&
            elevenLabsApiKey.isNotBlank()

    /** Returns a string representation with all API keys masked to prevent accidental log exposure. */
    override fun toString(): String =
        "SavedSettings(" +
            "openRouterApiKey=${maskApiKey(openRouterApiKey)}, " +
            "geminiApiKey=${maskApiKey(geminiApiKey)}, " +
            "meshyApiKey=${maskApiKey(meshyApiKey)}, " +
            "sunoApiKey=${maskApiKey(sunoApiKey)}, " +
            "elevenLabsApiKey=${maskApiKey(elevenLabsApiKey)}, " +
            "nanoBananaModel=$nanoBananaModel, " +
            "darkMode=$darkMode)"
}

/**
 * Reads and writes [SavedSettings] to an encrypted JSON file in the user's home directory.
 *
 * Settings are encrypted at rest using AES-256-GCM via [KeyEncryption]. The on-disk
 * format is an [EncryptedPayload] JSON object. Legacy plaintext `SavedSettings` files
 * are auto-detected and migrated to the encrypted format on first load.
 *
 * Uses atomic write-then-move to prevent corruption from interrupted saves.
 *
 * @param settingsDir Directory where `settings.json` is stored (defaults to `~/.gameharness`).
 * @param encryption Encryption engine to use (defaults to a new [KeyEncryption] instance;
 *     overridable for testing).
 */
class SettingsManager(
    private val settingsDir: Path = Paths.get(System.getProperty("user.home"), ".gameharness"),
    private val encryption: KeyEncryption = KeyEncryption(settingsDir)
) {
    private val log = LoggerFactory.getLogger(SettingsManager::class.java)

    private val settingsFile: Path = settingsDir.resolve("settings.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Returns true if the settings file exists on disk. */
    fun exists(): Boolean = Files.exists(settingsFile)

    /**
     * Loads settings from disk, or returns null if the file is missing or unreadable.
     *
     * Attempts to read the file as an [EncryptedPayload] first. If that fails (e.g. the
     * file is a legacy plaintext [SavedSettings]), it tries the plaintext path and
     * automatically re-saves in encrypted format (migration).
     */
    fun load(): SavedSettings? {
        if (!Files.exists(settingsFile)) return null
        return try {
            val content = Files.readString(settingsFile)
            if (isEncryptedFormat(content)) {
                tryLoadEncrypted(content)
            } else {
                tryLoadPlaintext(content)
            }
        } catch (e: Exception) {
            log.warn("Failed to load settings: {}", e.message)
            null
        }
    }

    /**
     * Persists settings to disk atomically, encrypted with AES-256-GCM.
     *
     * The [SavedSettings] is serialized to JSON, encrypted, and the resulting
     * [EncryptedPayload] JSON is written via a temp file + atomic move.
     */
    fun save(settings: SavedSettings) {
        settingsDir.ensureDirectoryExists()
        // Defensive trim: strip whitespace from all API keys before persisting
        val sanitized = settings.copy(
            openRouterApiKey = settings.openRouterApiKey.trim(),
            geminiApiKey = settings.geminiApiKey.trim(),
            meshyApiKey = settings.meshyApiKey.trim(),
            sunoApiKey = settings.sunoApiKey.trim(),
            elevenLabsApiKey = settings.elevenLabsApiKey.trim()
        )
        val plainJson = json.encodeToString(sanitized)
        val payload = encryption.encrypt(plainJson)
        val encryptedJson = json.encodeToString(payload)
        val tempFile = settingsDir.resolve("settings.json.tmp")
        Files.writeString(tempFile, encryptedJson)
        Files.move(tempFile, settingsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // ── Format detection ────────────────────────────────────────────────

    /**
     * Checks whether [content] is in the [EncryptedPayload] JSON format
     * by inspecting top-level keys for `"iv"`, `"data"`, and `"version"`.
     */
    private fun isEncryptedFormat(content: String): Boolean {
        return try {
            val obj = Json.parseToJsonElement(content).jsonObject
            "iv" in obj && "data" in obj && "version" in obj
        } catch (_: Exception) {
            false
        }
    }

    // ── Load paths ──────────────────────────────────────────────────────

    private fun tryLoadEncrypted(content: String): SavedSettings? {
        return try {
            val payload = json.decodeFromString<EncryptedPayload>(content)
            val plainJson = encryption.decrypt(payload)
            json.decodeFromString<SavedSettings>(plainJson)
        } catch (e: Exception) {
            log.warn("Failed to decrypt settings: {}", e.message)
            null
        }
    }

    private fun tryLoadPlaintext(content: String): SavedSettings? {
        return try {
            val settings = json.decodeFromString<SavedSettings>(content)
            log.info("Migrating plaintext settings to encrypted format")
            save(settings)
            settings
        } catch (e: Exception) {
            log.warn("Failed to parse plaintext settings: {}", e.message)
            null
        }
    }
}
