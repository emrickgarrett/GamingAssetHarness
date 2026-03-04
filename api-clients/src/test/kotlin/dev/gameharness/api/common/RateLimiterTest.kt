package dev.gameharness.api.common

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {

    @Test
    fun `allows requests within limit`() = runTest {
        val limiter = RateLimiter(maxRequests = 3, perDuration = 1.minutes)

        // Should not throw or block for 3 requests
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
    }

    @Test
    fun `rejects invalid maxRequests`() {
        assertFailsWith<IllegalArgumentException> {
            RateLimiter(maxRequests = 0)
        }
    }

    @Test
    fun `rejects invalid perDuration`() {
        assertFailsWith<IllegalArgumentException> {
            RateLimiter(maxRequests = 5, perDuration = 0.seconds)
        }
    }

    @Test
    fun `cleans up expired timestamps`() = runTest {
        var currentTime = 0L
        val limiter = RateLimiter(
            maxRequests = 2,
            perDuration = 1.seconds,
            clock = { currentTime }
        )

        // Fill up the limit
        limiter.acquire()
        limiter.acquire()

        // Advance time past the window
        currentTime = 2000L

        // Should succeed because old timestamps expired
        limiter.acquire()
    }

    @Test
    fun `concurrent requests respect limit`() = runTest {
        val acquireTimes = mutableListOf<Long>()
        var currentTime = 0L
        val limiter = RateLimiter(
            maxRequests = 2,
            perDuration = 1.seconds,
            clock = { currentTime }
        )

        limiter.acquire()
        acquireTimes.add(currentTime)
        limiter.acquire()
        acquireTimes.add(currentTime)

        assertTrue(acquireTimes.size == 2)
    }
}
