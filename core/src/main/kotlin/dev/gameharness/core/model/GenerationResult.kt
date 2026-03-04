package dev.gameharness.core.model

/**
 * Outcome of an asset generation API call.
 *
 * Generation may complete immediately ([Completed]), require polling ([InProgress]),
 * or fail ([Failed]). API clients return this type to let the agent decide how to proceed.
 */
sealed class GenerationResult {
    /**
     * Generation finished successfully.
     *
     * @property asset Metadata for the generated asset (file path not yet populated).
     * @property fileBytes Raw bytes of the generated file.
     */
    data class Completed(
        val asset: GeneratedAsset,
        val fileBytes: ByteArray
    ) : GenerationResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Completed) return false
            return asset == other.asset && fileBytes.contentEquals(other.fileBytes)
        }

        override fun hashCode(): Int {
            var result = asset.hashCode()
            result = 31 * result + fileBytes.contentHashCode()
            return result
        }
    }

    /**
     * Generation is still running on the remote service and needs to be polled.
     *
     * @property taskId Remote task identifier for polling status.
     * @property progressPercent Estimated completion percentage (0-100).
     */
    data class InProgress(
        val taskId: String,
        val progressPercent: Int = 0
    ) : GenerationResult()

    /**
     * Generation failed.
     *
     * @property error Human-readable error description.
     * @property retryable True if the error is transient and the request can be retried.
     */
    data class Failed(
        val error: String,
        val retryable: Boolean = false
    ) : GenerationResult()
}
