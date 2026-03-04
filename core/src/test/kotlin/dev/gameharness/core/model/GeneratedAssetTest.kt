package dev.gameharness.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeneratedAssetTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `serialization round-trip preserves all fields`() {
        val asset = GeneratedAsset(
            id = "test-id-123",
            type = AssetType.SPRITE,
            fileName = "hero.png",
            filePath = "/workspace/sprites/hero.png",
            format = "png",
            description = "A pixel art hero character",
            generationParams = mapOf("style" to "16bit", "aspectRatio" to "1:1"),
            sizeBytes = 4096,
            createdAt = 1700000000000,
            status = AssetDecision.APPROVED
        )

        val encoded = json.encodeToString(asset)
        val decoded = json.decodeFromString<GeneratedAsset>(encoded)

        assertEquals(asset, decoded)
    }

    @Test
    fun `default values are applied correctly`() {
        val asset = GeneratedAsset(
            id = "test-id",
            type = AssetType.MUSIC,
            fileName = "battle.mp3",
            filePath = "/workspace/music/battle.mp3",
            format = "mp3",
            description = "Battle music"
        )

        assertEquals(AssetDecision.PENDING, asset.status)
        assertEquals(0L, asset.sizeBytes)
        assertEquals(emptyMap(), asset.generationParams)
    }

    @Test
    fun `GenerationRequest rejects blank description`() {
        assertFailsWith<IllegalArgumentException> {
            GenerationRequest(description = "", type = AssetType.SPRITE)
        }
    }

    @Test
    fun `GenerationRequest accepts valid description`() {
        val request = GenerationRequest(
            description = "A sword sprite",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit")
        )
        assertEquals("A sword sprite", request.description)
        assertEquals(AssetType.SPRITE, request.type)
    }

    @Test
    fun `Workspace rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            Workspace(name = "", directoryPath = "/some/path")
        }
    }

    @Test
    fun `ChatMessage serialization round-trip`() {
        val message = ChatMessage(
            role = ChatRole.ASSISTANT,
            content = "Here is your sprite!",
            timestamp = 1700000000000,
            assetRef = "asset-123"
        )

        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<ChatMessage>(encoded)

        assertEquals(message, decoded)
    }

    @Test
    fun `AssetType has correct subdirectories`() {
        assertEquals("sprites", AssetType.SPRITE.subdirectory)
        assertEquals("models", AssetType.MODEL_3D.subdirectory)
        assertEquals("music", AssetType.MUSIC.subdirectory)
        assertEquals("sfx", AssetType.SOUND_EFFECT.subdirectory)
    }

    @Test
    fun `GenerationResult Completed equality uses content comparison for ByteArray`() {
        val asset = GeneratedAsset(
            id = "id", type = AssetType.SPRITE, fileName = "f.png",
            filePath = "/f.png", format = "png", description = "test"
        )
        val bytes = byteArrayOf(1, 2, 3)

        val r1 = GenerationResult.Completed(asset, bytes.copyOf())
        val r2 = GenerationResult.Completed(asset, bytes.copyOf())

        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())
    }
}
