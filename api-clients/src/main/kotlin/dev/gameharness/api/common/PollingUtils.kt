package dev.gameharness.api.common

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Generic polling loop that repeatedly calls [poll] until the result satisfies
 * [isComplete] or [isFailed], or until [maxDuration] is exceeded.
 *
 * Progress is reported via [onProgress] using a time-based linear interpolation
 * between [baseProgress] and [maxProgress].
 *
 * @param maxDuration maximum wall-clock time before throwing [ApiException.Timeout]
 * @param pollInterval delay between consecutive poll calls
 * @param onProgress optional callback receiving (progressPercent, statusMessage)
 * @param progressMessage lambda producing a human-readable status from elapsed seconds
 * @param baseProgress progress percentage reported at the start of polling
 * @param maxProgress progress percentage reported just before completion
 * @param poll suspend function that executes a single poll request and returns the result
 * @param isComplete predicate returning `true` when the result indicates success
 * @param isFailed predicate returning an error message when the result indicates failure, or `null` otherwise
 * @param timeoutMessage message for the [ApiException.Timeout] thrown on timeout
 * @return the first result for which [isComplete] returns `true`
 */
internal suspend fun <T> pollUntilComplete(
    maxDuration: Duration,
    pollInterval: Duration,
    onProgress: ((Int, String) -> Unit)?,
    progressMessage: (elapsedSeconds: Long) -> String,
    baseProgress: Int,
    maxProgress: Int,
    poll: suspend () -> T,
    isComplete: (T) -> Boolean,
    isFailed: (T) -> String?,
    timeoutMessage: String
): T {
    val startTime = TimeSource.Monotonic.markNow()
    val maxSeconds = maxDuration.inWholeSeconds

    while (startTime.elapsedNow() < maxDuration) {
        val elapsed = startTime.elapsedNow().inWholeSeconds
        val progress = ((elapsed.toFloat() / maxSeconds) * (maxProgress - baseProgress) + baseProgress)
            .toInt().coerceIn(baseProgress, maxProgress)
        onProgress?.invoke(progress, progressMessage(elapsed))

        val result = poll()

        if (isComplete(result)) return result

        val errorMessage = isFailed(result)
        if (errorMessage != null) throw ApiException.GenerationFailed(errorMessage)

        delay(pollInterval)
    }

    throw ApiException.Timeout(timeoutMessage)
}

/**
 * Downloads a file from the given [url] and returns the raw bytes.
 *
 * @param url the URL to download from
 * @param errorContext human-readable description of what is being downloaded, used in error messages
 * @return the response body as a byte array
 * @throws ApiException.GenerationFailed if the HTTP response status is not 200
 */
internal suspend fun HttpClient.downloadFileBytes(url: String, errorContext: String): ByteArray {
    val response = get(url)
    if (response.status.value != 200) {
        throw ApiException.GenerationFailed("Failed to download $errorContext: HTTP ${response.status.value}")
    }
    return response.body()
}
