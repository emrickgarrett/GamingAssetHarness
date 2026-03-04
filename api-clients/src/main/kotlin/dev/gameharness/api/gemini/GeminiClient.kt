package dev.gameharness.api.gemini

import dev.gameharness.api.common.ApiException
import dev.gameharness.api.common.AssetGenerationClient
import dev.gameharness.api.common.createDefaultHttpClient
import dev.gameharness.api.common.generateAssetIdentifiers
import dev.gameharness.core.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

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
        val prompt = buildPrompt(request)
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

        val geminiRequest = GeminiRequest(
            contents = listOf(GeminiContent(parts = parts)),
            generationConfig = GeminiGenerationConfig(
                responseModalities = listOf("IMAGE", "TEXT")
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
            200 -> parseSuccessResponse(response.body(), request)
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
        request: GenerationRequest
    ): GenerationResult {
        val candidate = geminiResponse.candidates?.firstOrNull()
            ?: return GenerationResult.Failed("No candidates in response")

        val imagePart = candidate.content?.parts?.find { it.inlineData != null }
            ?: return GenerationResult.Failed("No image data in response")

        val imageData = imagePart.inlineData!!
        val bytes = try {
            Base64.getDecoder().decode(imageData.data)
        } catch (e: IllegalArgumentException) {
            return GenerationResult.Failed("Invalid image data from API")
        }
        val format = when {
            imageData.mimeType.contains("png") -> "png"
            imageData.mimeType.contains("webp") -> "webp"
            imageData.mimeType.contains("jpeg") || imageData.mimeType.contains("jpg") -> "jpg"
            else -> "png"
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

    private fun buildPrompt(request: GenerationRequest): String {
        val style = request.params["style"] ?: "16bit"
        val aspectRatio = request.params["aspectRatio"] ?: "1:1"
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
            append("Transparent background. Suitable for use in a 2D game engine. ")
            append("Clean edges, no artifacts.")
        }
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

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-2.5-flash-image"
    }
}
