package dev.gameharness.api.common

import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GenerationRequest
import dev.gameharness.core.model.GenerationResult

/**
 * Common interface for all external asset generation API clients.
 *
 * Each implementation wraps a specific third-party API (Gemini, Meshy, Suno, ElevenLabs)
 * and handles request construction, polling (if applicable), and response parsing.
 * Clients are [AutoCloseable]-like: call [close] when the client is no longer needed
 * to release the underlying HTTP connection pool.
 */
interface AssetGenerationClient {
    /** The asset category this client produces. */
    val assetType: AssetType

    /**
     * Generates an asset from the given [request].
     *
     * @return a [GenerationResult] indicating success, failure, or in-progress status
     * @throws ApiException on authentication, rate-limiting, or unrecoverable API errors
     */
    suspend fun generate(request: GenerationRequest): GenerationResult

    /** Releases the underlying HTTP client and connection pool. */
    fun close()
}
