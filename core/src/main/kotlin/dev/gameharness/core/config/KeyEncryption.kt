package dev.gameharness.core.config

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted payload written to disk in place of plaintext settings.
 *
 * The JSON structure (`version`, `iv`, `data`) is intentionally distinct from
 * [SavedSettings] so the two formats can be reliably discriminated at load time.
 *
 * @property version Encryption format version (currently always 1).
 * @property iv Base64-encoded 12-byte GCM initialization vector.
 * @property data Base64-encoded ciphertext including the GCM authentication tag.
 */
@Serializable
data class EncryptedPayload(
    val version: Int = 1,
    val iv: String,
    val data: String
)

/**
 * AES-256-GCM encryption engine backed by a machine-bound master key file.
 *
 * The master key is a 256-bit random value stored in `<settingsDir>/.keystore`.
 * On first use the key is generated via [SecureRandom] and the file permissions
 * are restricted to owner-only access. Subsequent calls load the existing key.
 *
 * **Threat model:** protects against casual file browsing, accidental sharing,
 * and backup exposure. Does *not* protect against a determined attacker with
 * full local system access (standard trade-off for desktop applications without
 * user-password-based key derivation).
 *
 * @param settingsDir directory where the `.keystore` file is stored
 *     (typically `~/.gameharness`).
 */
class KeyEncryption(private val settingsDir: Path) {

    private val log = LoggerFactory.getLogger(KeyEncryption::class.java)

    private val keystoreFile: Path = settingsDir.resolve(".keystore")

    /** Lazily loaded/generated master key — triggers key file creation on first access. */
    private val masterKey: SecretKey by lazy { loadOrGenerateKey() }

    /** Returns `true` if the master key file exists on disk. */
    fun hasMasterKey(): Boolean = Files.exists(keystoreFile)

    /**
     * Encrypts [plaintext] using AES-256-GCM with a fresh random IV.
     *
     * Each call produces different ciphertext even for identical input because
     * a new 12-byte nonce is generated via [SecureRandom].
     */
    fun encrypt(plaintext: String): EncryptedPayload {
        val nonce = ByteArray(GCM_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            version = 1,
            iv = Base64.getEncoder().encodeToString(nonce),
            data = Base64.getEncoder().encodeToString(ciphertext)
        )
    }

    /**
     * Decrypts an [EncryptedPayload] back to the original plaintext.
     *
     * @throws IllegalArgumentException if [payload] has an unsupported [EncryptedPayload.version].
     * @throws javax.crypto.AEADBadTagException if the ciphertext or IV has been tampered with.
     */
    fun decrypt(payload: EncryptedPayload): String {
        require(payload.version == 1) { "Unsupported encryption version: ${payload.version}" }
        val nonce = Base64.getDecoder().decode(payload.iv)
        val ciphertext = Base64.getDecoder().decode(payload.data)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // ── Master key lifecycle ────────────────────────────────────────────

    private fun loadOrGenerateKey(): SecretKey {
        if (Files.exists(keystoreFile)) {
            try {
                log.info("Loading master encryption key")
                val bytes = Files.readAllBytes(keystoreFile)
                if (bytes.size == KEY_SIZE_BYTES) {
                    return SecretKeySpec(bytes, "AES")
                }
                log.warn("Keystore has unexpected size ({}), regenerating", bytes.size)
            } catch (e: Exception) {
                log.warn("Cannot read keystore ({}), regenerating", e.message)
            }
            // Delete the unreadable/corrupt keystore so we can recreate it
            try {
                Files.deleteIfExists(keystoreFile)
            } catch (e: Exception) {
                log.error("Cannot delete corrupt keystore: {}", e.message)
                throw e
            }
        }

        log.info("Generating new master encryption key")
        val bytes = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        settingsDir.let { if (!Files.exists(it)) Files.createDirectories(it) }
        Files.write(keystoreFile, bytes)
        restrictPermissions(keystoreFile)
        return SecretKeySpec(bytes, "AES")
    }

    // ── File permission helpers ─────────────────────────────────────────

    private fun restrictPermissions(path: Path) {
        try {
            val perms = PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Not a POSIX filesystem (e.g. Windows/NTFS) — try ACL
            restrictPermissionsWindows(path)
        } catch (e: Exception) {
            log.warn("Failed to set POSIX permissions on keystore: {}", e.message)
        }
    }

    private fun restrictPermissionsWindows(path: Path) {
        // On Windows, replacing the entire ACL list (aclView.acl = listOf(...)) strips
        // inherited permissions that the JVM process needs, causing AccessDeniedException
        // on subsequent reads. The user's home directory already provides adequate
        // file-level isolation on Windows, so we rely on that plus encryption as the
        // primary protection rather than risking a self-lockout with aggressive ACLs.
        log.debug("Keystore created in user home directory (Windows — relying on home directory permissions)")
    }

    companion object {
        private const val KEY_SIZE_BYTES = 32        // AES-256
        private const val GCM_NONCE_BYTES = 12       // Standard GCM IV size
        private const val GCM_TAG_BITS = 128         // Full-size authentication tag
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    }
}

/**
 * Masks an API key for safe display in logs and `toString()` output.
 *
 * - `null` or blank → `"(empty)"`
 * - 4 characters or fewer → `"****"`
 * - Longer → first 4 characters + `"****"`
 *
 * Examples: `"sk-or-v1-abc123"` → `"sk-o****"`, `""` → `"(empty)"`, `"ab"` → `"****"`
 */
internal fun maskApiKey(key: String?): String {
    if (key.isNullOrBlank()) return "(empty)"
    if (key.length <= 4) return "****"
    return key.take(4) + "****"
}
