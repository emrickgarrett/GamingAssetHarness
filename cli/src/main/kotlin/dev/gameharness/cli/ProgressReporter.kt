package dev.gameharness.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProgressMessage(val progress: Int, val message: String)

class ProgressReporter {
    private val json = Json { prettyPrint = false }

    fun report(progress: Int, message: String) {
        val msg = ProgressMessage(progress, message)
        System.err.println(json.encodeToString(ProgressMessage.serializer(), msg))
        System.err.flush()
    }

    fun toCallback(): (Int, String) -> Unit = ::report
}
