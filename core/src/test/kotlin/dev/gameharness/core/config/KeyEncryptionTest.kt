package dev.gameharness.core.config

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.AEADBadTagException
import kotlin.test.*

class KeyEncryptionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var encryption: KeyEncryption

    @BeforeEach
    fun setup() {
        encryption = KeyEncryption(tempDir)
    }

    @Test
    fun `encrypt and decrypt round-trip`() {
        val original = "Hello, this is a secret message!"
        val payload = encryption.encrypt(original)
        val decrypted = encryption.decrypt(payload)
        assertEquals(original, decrypted)
    }

    @Test
    fun `different encryptions produce different ciphertext`() {
        val text = "same input both times"
        val payload1 = encryption.encrypt(text)
        val payload2 = encryption.encrypt(text)

        // IVs should differ (random nonce per call)
        assertNotEquals(payload1.iv, payload2.iv)
        // Ciphertext should differ
        assertNotEquals(payload1.data, payload2.data)
        // But both decrypt to the same value
        assertEquals(text, encryption.decrypt(payload1))
        assertEquals(text, encryption.decrypt(payload2))
    }

    @Test
    fun `decrypt with wrong key fails`() {
        val otherEncryption = KeyEncryption(tempDir.resolve("other"))
        val payload = encryption.encrypt("secret")

        assertFailsWith<Exception> {
            otherEncryption.decrypt(payload)
        }
    }

    @Test
    fun `master key file is created on first use`() {
        val keystoreFile = tempDir.resolve(".keystore")
        assertFalse(Files.exists(keystoreFile))

        encryption.encrypt("trigger key creation")

        assertTrue(Files.exists(keystoreFile))
    }

    @Test
    fun `master key file is 32 bytes`() {
        encryption.encrypt("trigger key creation")

        val keystoreFile = tempDir.resolve(".keystore")
        // Use Files.size() instead of readAllBytes to avoid ACL-related AccessDeniedException on Windows
        assertEquals(32L, Files.size(keystoreFile))
    }

    @Test
    fun `hasMasterKey returns false before first use`() {
        val freshEncryption = KeyEncryption(tempDir.resolve("fresh"))
        assertFalse(freshEncryption.hasMasterKey())
    }

    @Test
    fun `hasMasterKey returns true after encrypt`() {
        assertFalse(encryption.hasMasterKey())
        encryption.encrypt("trigger")
        assertTrue(encryption.hasMasterKey())
    }

    @Test
    fun `empty string encrypts and decrypts`() {
        val payload = encryption.encrypt("")
        assertEquals("", encryption.decrypt(payload))
    }

    @Test
    fun `unicode string encrypts and decrypts`() {
        val unicode = "Hello 🎮🎵🎨 日本語テスト café résumé"
        val payload = encryption.encrypt(unicode)
        assertEquals(unicode, encryption.decrypt(payload))
    }

    @Test
    fun `corrupt ciphertext fails decryption`() {
        val payload = encryption.encrypt("secret data")
        val corruptedPayload = payload.copy(data = payload.data.reversed())

        assertFailsWith<Exception> {
            encryption.decrypt(corruptedPayload)
        }
    }

    @Test
    fun `corrupt iv fails decryption`() {
        val payload = encryption.encrypt("secret data")
        // Replace IV with a different valid Base64 string of the same length
        val corruptedPayload = payload.copy(
            iv = java.util.Base64.getEncoder().encodeToString(ByteArray(12) { 0xFF.toByte() })
        )

        assertFailsWith<Exception> {
            encryption.decrypt(corruptedPayload)
        }
    }

    @Test
    fun `unsupported version throws IllegalArgumentException`() {
        val payload = encryption.encrypt("data")
        val futurePayload = payload.copy(version = 99)

        val exception = assertFailsWith<IllegalArgumentException> {
            encryption.decrypt(futurePayload)
        }
        assertTrue(exception.message!!.contains("Unsupported encryption version: 99"))
    }

    @Test
    fun `large payload encrypts and decrypts`() {
        val largeText = "x".repeat(100_000)
        val payload = encryption.encrypt(largeText)
        assertEquals(largeText, encryption.decrypt(payload))
    }

    @Test
    fun `payload version defaults to 1`() {
        val payload = encryption.encrypt("data")
        assertEquals(1, payload.version)
    }
}

class MaskApiKeyTest {

    @Test
    fun `null key returns empty`() {
        assertEquals("(empty)", maskApiKey(null))
    }

    @Test
    fun `blank key returns empty`() {
        assertEquals("(empty)", maskApiKey(""))
        assertEquals("(empty)", maskApiKey("   "))
    }

    @Test
    fun `short key is fully masked`() {
        assertEquals("****", maskApiKey("ab"))
        assertEquals("****", maskApiKey("abcd"))
    }

    @Test
    fun `long key shows first 4 chars`() {
        assertEquals("sk-o****", maskApiKey("sk-or-v1-abc123def456"))
    }

    @Test
    fun `exactly 5 chars shows first 4 plus mask`() {
        assertEquals("abcd****", maskApiKey("abcde"))
    }
}
