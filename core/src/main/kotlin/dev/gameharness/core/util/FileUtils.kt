package dev.gameharness.core.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Matches characters that are unsafe in file names. */
private val INVALID_CHAR_REGEX = Regex("[^a-zA-Z0-9._-]")
/** Collapses consecutive underscores introduced by sanitization. */
private val MULTIPLE_UNDERSCORE_REGEX = Regex("_+")

/**
 * Creates this directory and any missing parents if they do not already exist.
 *
 * @return This path, for chaining.
 */
fun Path.ensureDirectoryExists(): Path {
    if (!Files.exists(this)) {
        Files.createDirectories(this)
    }
    return this
}

/**
 * Writes bytes to this path, creating parent directories as needed.
 * Overwrites the file if it already exists.
 *
 * @return This path, for chaining.
 */
fun Path.writeBytesSafely(bytes: ByteArray): Path {
    parent?.ensureDirectoryExists()
    Files.write(this, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    return this
}

/**
 * Reads all bytes from this path after verifying that the file exists and is a regular file.
 *
 * @throws IllegalArgumentException if the file does not exist or is not a regular file.
 */
fun Path.readBytesSafely(): ByteArray {
    require(Files.exists(this)) { "File does not exist: $this" }
    require(Files.isRegularFile(this)) { "Not a regular file: $this" }
    return Files.readAllBytes(this)
}

/**
 * Replaces characters unsafe for file names with underscores, collapses consecutive
 * underscores, trims leading/trailing underscores, and truncates to 200 characters.
 *
 * Returns `"unnamed"` if the result would be blank.
 */
fun sanitizeFileName(name: String): String {
    return name
        .replace(INVALID_CHAR_REGEX, "_")
        .replace(MULTIPLE_UNDERSCORE_REGEX, "_")
        .trimStart('_')
        .trimEnd('_')
        .take(200)
        .ifBlank { "unnamed" }
}
