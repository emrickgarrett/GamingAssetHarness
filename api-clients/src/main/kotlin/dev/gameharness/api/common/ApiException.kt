package dev.gameharness.api.common

/**
 * Sealed hierarchy of exceptions thrown by [AssetGenerationClient] implementations.
 *
 * Callers can pattern-match on the subclass to decide whether to retry, surface an error
 * to the user, or take other corrective action.
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The API returned HTTP 429; the caller should back off for [retryAfterMs] milliseconds. */
    class RateLimited(val retryAfterMs: Long = 0) : ApiException("Rate limited, retry after ${retryAfterMs}ms")
    /** The API key is invalid or the account lacks permission (HTTP 401/403). */
    class AuthenticationFailed(msg: String = "Authentication failed") : ApiException(msg)
    /** The generation request was accepted but the API reported a failure during processing. */
    class GenerationFailed(msg: String) : ApiException(msg)
    /** A transport-level error prevented the request from completing. */
    class NetworkError(cause: Throwable) : ApiException("Network error: ${cause.message}", cause)
    /** A polling loop exceeded its maximum wait time without reaching a terminal state. */
    class Timeout(msg: String = "Request timed out") : ApiException(msg)
}
