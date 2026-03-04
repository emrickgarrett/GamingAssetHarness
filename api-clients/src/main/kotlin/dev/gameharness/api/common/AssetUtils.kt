package dev.gameharness.api.common

import dev.gameharness.core.util.sanitizeFileName
import java.util.UUID

/**
 * Generates a unique asset ID and a sanitized file name from the given description and file extension.
 *
 * The file name is derived from the first 50 characters of [description], sanitized for filesystem
 * safety, suffixed with a UUID, and given the specified [extension].
 *
 * @param description the human-readable description of the asset (e.g. "explosion" or "medieval castle")
 * @param extension the file extension without a leading dot (e.g. "png", "mp3", "glb")
 * @return a pair of (assetId, fileName) where assetId is a UUID string
 */
fun generateAssetIdentifiers(description: String, extension: String): Pair<String, String> {
    val assetId = UUID.randomUUID().toString()
    val fileName = "${sanitizeFileName(description.take(50))}_$assetId.$extension"
    return assetId to fileName
}
