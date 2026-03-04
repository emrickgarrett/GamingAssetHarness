package dev.gameharness.api.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Coroutine-safe sliding-window rate limiter.
 *
 * Allows at most [maxRequests] calls to [acquire] within any rolling [perDuration] window.
 * When the limit is reached, [acquire] suspends until a slot becomes available. Internal
 * state is protected by a [Mutex] so it is safe to call from multiple coroutines concurrently.
 *
 * @param maxRequests maximum number of requests allowed in the window (must be positive)
 * @param perDuration length of the sliding window (defaults to 1 minute)
 * @param clock time source in epoch milliseconds, injectable for testing
 */
class RateLimiter(
    private val maxRequests: Int,
    private val perDuration: Duration = 1.minutes,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()
    private val windowMs = perDuration.inWholeMilliseconds

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(perDuration.isPositive()) { "perDuration must be positive" }
    }

    suspend fun acquire() {
        while (true) {
            val waitTime = mutex.withLock {
                val now = clock()
                // Remove expired timestamps
                while (timestamps.isNotEmpty() && (now - timestamps.first()) >= windowMs) {
                    timestamps.removeFirst()
                }

                if (timestamps.size >= maxRequests) {
                    val wait = windowMs - (now - timestamps.first())
                    if (wait > 0) wait else 0L
                } else {
                    timestamps.addLast(now)
                    return // Slot acquired
                }
            }

            // Delay outside the lock so other coroutines aren't blocked
            if (waitTime > 0) {
                delay(waitTime)
            }
            // Re-check under the lock
        }
    }
}
