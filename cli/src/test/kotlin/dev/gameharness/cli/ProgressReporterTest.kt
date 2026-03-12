package dev.gameharness.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals

class ProgressReporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val baos = ByteArrayOutputStream()
        System.setErr(PrintStream(baos))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return baos.toString().trim()
    }

    @Test
    fun `reports progress as JSON to stderr`() {
        val reporter = ProgressReporter()
        val output = captureStderr {
            reporter.report(45, "Processing model...")
        }

        val parsed = json.decodeFromString(ProgressMessage.serializer(), output)
        assertEquals(45, parsed.progress)
        assertEquals("Processing model...", parsed.message)
    }

    @Test
    fun `toCallback returns working function`() {
        val reporter = ProgressReporter()
        val callback = reporter.toCallback()
        val output = captureStderr {
            callback(90, "Almost done")
        }

        val parsed = json.decodeFromString(ProgressMessage.serializer(), output)
        assertEquals(90, parsed.progress)
        assertEquals("Almost done", parsed.message)
    }
}
