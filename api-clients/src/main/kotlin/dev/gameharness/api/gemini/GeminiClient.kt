package dev.gameharness.api.gemini

import dev.gameharness.api.common.ApiException
import dev.gameharness.api.common.AssetGenerationClient
import dev.gameharness.api.common.createDefaultHttpClient
import dev.gameharness.api.common.generateAssetIdentifiers
import dev.gameharness.core.model.*
import dev.gameharness.core.util.SpriteSheetSplitter
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.awt.Color
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Client for the Gemini (NanoBanana) image generation API that produces 2D sprite textures.
 *
 * Sends a single request containing the text prompt (and optional reference images encoded
 * as inline Base64) and receives the generated image directly in the response. No polling
 * is required.
 *
 * @param apiKey Gemini API key for authentication (sent as `x-goog-api-key` header)
 * @param model the Gemini model to use (defaults to [DEFAULT_MODEL])
 * @param httpClient Ktor HTTP client (defaults to [createDefaultHttpClient])
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val httpClient: HttpClient = createDefaultHttpClient()
) : AssetGenerationClient {

    private val endpoint = "$BASE_URL/models/$model:generateContent"

    override val assetType = AssetType.SPRITE

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val removeBg = request.params["removeBg"] != "false"
        val chromaKey = if (removeBg) selectChromaKeyColor(request.description) else null
        val prompt = buildPrompt(request, chromaKey)
        val parts = mutableListOf<GeminiPart>()

        // Include reference images if provided
        for (refPath in request.referenceImagePaths) {
            try {
                val imagePath = Path.of(refPath)
                val imageBytes = Files.readAllBytes(imagePath)
                val mimeType = detectMimeType(imagePath)
                val base64Data = Base64.getEncoder().encodeToString(imageBytes)
                parts.add(GeminiPart(inlineData = GeminiInlineData(mimeType, base64Data)))
            } catch (e: Exception) {
                return GenerationResult.Failed(
                    error = "Failed to read reference image '$refPath': ${e.message}",
                    retryable = false
                )
            }
        }
        parts.add(GeminiPart(text = prompt))

        val imageSize = request.params["imageSize"]?.takeIf { it in VALID_IMAGE_SIZES }
        val aspectRatio = request.params["aspectRatio"]?.takeIf { it in VALID_ASPECT_RATIOS }
        val imageConfig = if (imageSize != null || aspectRatio != null) {
            GeminiImageConfig(imageSize = imageSize, aspectRatio = aspectRatio)
        } else null

        val geminiRequest = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            generationConfig = GeminiGenerationConfig(
                responseModalities = listOf("IMAGE", "TEXT"),
                imageConfig = imageConfig
            )
        )

        val response = try {
            httpClient.post(endpoint) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(geminiRequest)
            }
        } catch (e: Exception) {
            return GenerationResult.Failed(
                error = "Network error: ${e.message}",
                retryable = true
            )
        }

        return when (response.status.value) {
            200 -> parseSuccessResponse(response.body(), request, chromaKey)
            401, 403 -> throw ApiException.AuthenticationFailed("Invalid Gemini API key")
            429 -> throw ApiException.RateLimited()
            else -> {
                val body = try { response.body<GeminiResponse>() } catch (_: Exception) { null }
                GenerationResult.Failed(
                    error = body?.error?.message ?: "HTTP ${response.status.value}",
                    retryable = response.status.value >= 500
                )
            }
        }
    }

    private fun parseSuccessResponse(
        geminiResponse: GeminiResponse,
        request: GenerationRequest,
        chromaKey: ChromaKeyConfig?
    ): GenerationResult {
        val candidate = geminiResponse.candidates?.firstOrNull()
            ?: return GenerationResult.Failed("No candidates in response")

        val imagePart = candidate.content?.parts?.find { it.inlineData != null }
            ?: return GenerationResult.Failed("No image data in response")

        val imageData = imagePart.inlineData!!
        var bytes = try {
            Base64.getDecoder().decode(imageData.data)
        } catch (e: IllegalArgumentException) {
            return GenerationResult.Failed("Invalid image data from API")
        }
        var format = when {
            imageData.mimeType.contains("png") -> "png"
            imageData.mimeType.contains("webp") -> "webp"
            imageData.mimeType.contains("jpeg") || imageData.mimeType.contains("jpg") -> "jpg"
            else -> "png"
        }

        // Remove chroma key background if active (default)
        if (chromaKey != null) {
            try {
                bytes = removeBackground(bytes, chromaKey)
                format = "png" // Always PNG after bg removal to preserve alpha
            } catch (_: Exception) {
                // If image processing fails, keep original bytes
            }
        }

        val (assetId, fileName) = generateAssetIdentifiers(request.description, format)

        val asset = GeneratedAsset(
            id = assetId,
            type = AssetType.SPRITE,
            fileName = fileName,
            filePath = "",
            format = format,
            description = request.description,
            generationParams = request.params,
            sizeBytes = bytes.size.toLong()
        )

        return GenerationResult.Completed(asset, bytes)
    }

    private fun buildPrompt(request: GenerationRequest, chromaKey: ChromaKeyConfig?): String {
        val style = request.params["style"] ?: "16bit"
        val aspectRatio = request.params["aspectRatio"] ?: "1:1"
        val width = request.params["width"]
        val height = request.params["height"]
        return buildString {
            if (request.referenceImagePaths.isNotEmpty()) {
                val count = request.referenceImagePaths.size
                val imageWord = if (count == 1) "reference image" else "$count reference images"
                append("Using the attached $imageWord as visual guidance, ")
                append("generate a game sprite: ${request.description}. ")
            } else {
                append("Generate a game sprite: ${request.description}. ")
            }
            append("Style: $style pixel art. ")
            append("Aspect ratio: $aspectRatio. ")
            if (width != null && height != null) {
                append("Image dimensions: ${width}x${height} pixels. ")
            } else if (width != null) {
                append("Image width: ${width} pixels. ")
            } else if (height != null) {
                append("Image height: ${height} pixels. ")
            }
            if (chromaKey != null) {
                append(
                    "IMPORTANT: The background MUST be a perfectly uniform, solid, flat ${chromaKey.name} color " +
                    "(hex ${chromaKey.hex}). Every single background pixel must be EXACTLY this color " +
                    "with NO variation. NO noise, NO texture, NO gradients, NO shading, NO shadows, " +
                    "NO dark areas in the background. The sprite must have sharp, clean, hard edges " +
                    "against the ${chromaKey.name} background with NO anti-aliasing, NO feathering, and NO color " +
                    "bleeding between the sprite and the background. Think of this as a chroma key " +
                    "screen — the background must be perfectly uniform for automated removal. "
                )
            } else {
                append("Transparent background. ")
            }
            append("Suitable for use in a 2D game engine. Clean edges, no artifacts.")
        }
    }

    /**
     * Removes the chroma key background from the image bytes using flood-fill
     * from the image borders, then runs multiple de-fringe passes to clean up
     * anti-aliasing artifacts. Each pass erodes one pixel layer of fringed
     * edge pixels, handling multi-pixel anti-aliasing gradients.
     * Returns PNG-encoded bytes.
     */
    private fun removeBackground(bytes: ByteArray, chromaKey: ChromaKeyConfig): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: throw IllegalStateException("Could not decode image for background removal")
        val floodFilled = SpriteSheetSplitter.removeBackgroundFloodFill(
            image,
            bgColor = chromaKey.color,
            tolerance = CHROMA_KEY_TOLERANCE
        )
        // Run multiple defringe passes to remove multi-pixel anti-aliasing gradients
        var result = floodFilled
        repeat(DEFRINGE_PASSES) {
            result = SpriteSheetSplitter.defringeEdges(
                result,
                bgColor = chromaKey.color,
                tolerance = DEFRINGE_TOLERANCE
            )
        }
        return SpriteSheetSplitter.tileToBytes(result)
    }

    private fun detectMimeType(path: Path): String {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            else -> "image/png"
        }
    }

    override fun close() {
        httpClient.close()
    }

    /** Configuration for a chroma key background color. */
    data class ChromaKeyConfig(val color: Color, val hex: String, val name: String)

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-2.5-flash-image"

        val VALID_IMAGE_SIZES = setOf("512", "1K", "2K", "4K")
        val VALID_ASPECT_RATIOS = setOf(
            "1:1", "3:2", "2:3", "3:4", "4:3",
            "4:5", "5:4", "9:16", "16:9", "21:9"
        )

        /** Chroma key colors for background removal. */
        val CHROMA_GREEN = ChromaKeyConfig(Color(0x00, 0xB1, 0x40), "#00b140", "green")
        val CHROMA_MAGENTA = ChromaKeyConfig(Color(0xFF, 0x00, 0xFF), "#ff00ff", "magenta")
        val CHROMA_BLUE = ChromaKeyConfig(Color(0x00, 0x00, 0xFF), "#0000ff", "blue")

        /** Tolerance for flood-fill chroma key matching (Manhattan distance in RGB). */
        const val CHROMA_KEY_TOLERANCE = 60
        /** Wider tolerance for de-fringing anti-aliased edge pixels. */
        const val DEFRINGE_TOLERANCE = 120
        /** Number of de-fringe passes to run (each pass erodes one pixel layer). */
        const val DEFRINGE_PASSES = 2

        /** Keywords that suggest the sprite contains green elements. */
        private val GREEN_KEYWORDS = setOf(
            "green", "grass", "tree", "leaf", "forest", "emerald", "slime", "vine",
            "jungle", "plant", "moss", "cactus", "frog", "lizard", "snake", "goblin",
            "orc", "zombie", "nature", "herb", "shrub", "bush", "hedge", "swamp",
            "algae", "seaweed", "lime", "mint", "olive", "jade", "sage", "teal",
            "clover", "shamrock", "bamboo", "fern", "ivy", "sprout", "meadow"
        )

        /** Keywords that suggest the sprite contains magenta/pink/purple elements. */
        private val MAGENTA_KEYWORDS = setOf(
            "magenta", "pink", "purple", "violet", "fuchsia", "lavender", "rose",
            "lilac", "orchid", "plum", "mauve"
        )

        /**
         * Selects the best chroma key color based on the sprite description to
         * avoid conflicts with the sprite content.
         *
         * - Default: green (#00b140)
         * - If description mentions green-related content: magenta (#ff00ff)
         * - If both green and magenta/purple conflict: blue (#0000ff)
         */
        internal fun selectChromaKeyColor(description: String): ChromaKeyConfig {
            val lower = description.lowercase()
            val words = lower.split(Regex("[\\s,.:;!?()\\[\\]{}\"'/-]+")).toSet()
            val hasGreen = words.any { it in GREEN_KEYWORDS }
            val hasMagenta = words.any { it in MAGENTA_KEYWORDS }
            return when {
                hasGreen && hasMagenta -> CHROMA_BLUE
                hasGreen -> CHROMA_MAGENTA
                else -> CHROMA_GREEN
            }
        }
    }
}
