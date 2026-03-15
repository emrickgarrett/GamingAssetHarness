package dev.gameharness.api.gemini

import kotlinx.serialization.Serializable

/** Request body for the Gemini `generateContent` endpoint. */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

/** Image generation configuration for resolution and aspect ratio. */
@Serializable
data class GeminiImageConfig(
    val imageSize: String? = null,
    val aspectRatio: String? = null
)

@Serializable
data class GeminiGenerationConfig(
    val responseModalities: List<String>? = null,
    val imageConfig: GeminiImageConfig? = null
)

// Response models

/** Top-level response from the Gemini API, containing candidates or an error. */
@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null
)

/** Error details returned by the Gemini API on failure. */
@Serializable
data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)
