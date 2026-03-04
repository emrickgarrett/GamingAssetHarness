package dev.gameharness.core.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ensureDirectoryExists creates nested directories`() {
        val nested = tempDir.resolve("a/b/c")
        nested.ensureDirectoryExists()
        assertTrue(nested.toFile().isDirectory)
    }

    @Test
    fun `ensureDirectoryExists is idempotent`() {
        val dir = tempDir.resolve("existing")
        dir.ensureDirectoryExists()
        dir.ensureDirectoryExists() // should not throw
        assertTrue(dir.toFile().isDirectory)
    }

    @Test
    fun `writeBytesSafely creates parent directories and writes`() {
        val file = tempDir.resolve("sub/dir/test.bin")
        val data = byteArrayOf(1, 2, 3, 4, 5)
        file.writeBytesSafely(data)

        assertTrue(file.toFile().exists())
        assertContentEquals(data, file.toFile().readBytes())
    }

    @Test
    fun `readBytesSafely reads existing file`() {
        val file = tempDir.resolve("readable.bin")
        val data = byteArrayOf(10, 20, 30)
        file.writeBytesSafely(data)

        assertContentEquals(data, file.readBytesSafely())
    }

    @Test
    fun `readBytesSafely throws for nonexistent file`() {
        assertFailsWith<IllegalArgumentException> {
            tempDir.resolve("nonexistent.bin").readBytesSafely()
        }
    }

    @Test
    fun `sanitizeFileName removes special characters`() {
        assertEquals("hello_world", sanitizeFileName("hello world"))
        assertEquals("file_name", sanitizeFileName("file/name"))
        assertEquals("test.png", sanitizeFileName("test.png"))
        assertEquals("a-b_c", sanitizeFileName("a-b_c"))
    }

    @Test
    fun `sanitizeFileName collapses multiple underscores`() {
        assertEquals("a_b", sanitizeFileName("a///b"))
    }

    @Test
    fun `sanitizeFileName returns unnamed for blank input`() {
        assertEquals("unnamed", sanitizeFileName(""))
        assertEquals("unnamed", sanitizeFileName("   "))
    }

    @Test
    fun `sanitizeFileName truncates long names`() {
        val longName = "a".repeat(300)
        val sanitized = sanitizeFileName(longName)
        assertTrue(sanitized.length <= 200)
    }
}
