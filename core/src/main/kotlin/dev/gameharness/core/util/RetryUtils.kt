package dev.gameharness.core.util

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for exponential-backoff retry behavior.
 *
 * @property maxAttempts Total number of attempts (must be >= 1; includes the initial try).
 * @property initialDelay Delay before the first retry.
 * @property maxDelay Upper bound on the delay between retries.
 * @property multiplier Factor by which the delay grows after each retry.
 */
data class RetryConfig(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val multiplier: Double = 2.0
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
        require(initialDelay.isPositive()) { "initialDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must be >= initialDelay" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
    }
}

/**
 * Executes [block] with exponential-backoff retries.
 *
 * On each failure, the exception is passed to [retryIf]; if it returns false
 * (or the maximum attempts are exhausted), the exception is rethrown immediately.
 *
 * @param config Retry timing and attempt limits.
 * @param retryIf Predicate that decides whether a given exception is worth retrying.
 * @param block The suspending operation to attempt. Receives the zero-based attempt index.
 * @return The result of the first successful invocation of [block].
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(),
    retryIf: (Exception) -> Boolean = { true },
    block: suspend (attempt: Int) -> T
): T {
    var currentDelay = config.initialDelay
    var lastException: Exception? = null

    repeat(config.maxAttempts) { attempt ->
        try {
            return block(attempt)
        } catch (e: Exception) {
            lastException = e
            if (attempt == config.maxAttempts - 1 || !retryIf(e)) {
                throw e
            }
            delay(currentDelay)
            currentDelay = min(
                (currentDelay.inWholeMilliseconds * config.multiplier).toLong(),
                config.maxDelay.inWholeMilliseconds
            ).milliseconds
        }
    }

    throw lastException ?: IllegalStateException("Retry exhausted without exception")
}
