package dev.gameharness.core.util

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryUtilsTest {

    @Test
    fun `succeeds on first attempt`() = runTest {
        var attempts = 0
        val result = withRetry(RetryConfig(maxAttempts = 3)) {
            attempts++
            "success"
        }

        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retries on failure and succeeds`() = runTest {
        var attempts = 0
        val result = withRetry(
            RetryConfig(maxAttempts = 3, initialDelay = 10.milliseconds)
        ) { attempt ->
            attempts++
            if (attempt < 2) throw RuntimeException("fail $attempt")
            "success on attempt $attempt"
        }

        assertEquals("success on attempt 2", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `throws after max attempts exhausted`() = runTest {
        var attempts = 0
        assertFailsWith<RuntimeException> {
            withRetry(
                RetryConfig(maxAttempts = 3, initialDelay = 10.milliseconds)
            ) {
                attempts++
                throw RuntimeException("always fails")
            }
        }

        assertEquals(3, attempts)
    }

    @Test
    fun `respects retryIf predicate`() = runTest {
        var attempts = 0
        assertFailsWith<java.io.IOException> {
            withRetry(
                config = RetryConfig(maxAttempts = 5, initialDelay = 10.milliseconds),
                retryIf = { it is RuntimeException }
            ) {
                attempts++
                throw java.io.IOException("not retryable")
            }
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `RetryConfig rejects invalid maxAttempts`() {
        assertFailsWith<IllegalArgumentException> {
            RetryConfig(maxAttempts = 0)
        }
    }

    @Test
    fun `RetryConfig rejects negative initialDelay`() {
        assertFailsWith<IllegalArgumentException> {
            RetryConfig(initialDelay = (-1).milliseconds)
        }
    }

    @Test
    fun `RetryConfig rejects maxDelay less than initialDelay`() {
        assertFailsWith<IllegalArgumentException> {
            RetryConfig(initialDelay = 5.seconds, maxDelay = 1.seconds)
        }
    }

    @Test
    fun `RetryConfig rejects multiplier less than 1`() {
        assertFailsWith<IllegalArgumentException> {
            RetryConfig(multiplier = 0.5)
        }
    }
}
