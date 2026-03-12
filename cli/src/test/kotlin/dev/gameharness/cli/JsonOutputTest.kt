package dev.gameharness.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonOutputTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return baos.toString().trim()
    }

    @Test
    fun `printSuccess outputs valid JSON with success true`() {
        val output = captureStdout {
            printSuccess("test.command", kotlinx.serialization.json.buildJsonObject {
                put("key", kotlinx.serialization.json.JsonPrimitive("value"))
            })
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertTrue(parsed.success)
        assertEquals("test.command", parsed.command)
        assertNull(parsed.error)
        assertEquals("value", parsed.data!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `printError outputs valid JSON with success false`() {
        val output = captureStdout {
            printError("test.command", "AUTH_FAILED", "Bad key")
        }

        val parsed = json.decodeFromString(CliResponse.serializer(), output)
        assertEquals(false, parsed.success)
        assertEquals("test.command", parsed.command)
        assertNull(parsed.data)
        assertEquals("AUTH_FAILED", parsed.error!!.code)
        assertEquals("Bad key", parsed.error!!.message)
    }

    @Test
    fun `assetToJson contains all fields`() {
        val obj = assetToJson(
            id = "abc-123",
            fileName = "test.png",
            filePath = "/tmp/test.png",
            type = "SPRITE",
            format = "png",
            description = "A test sprite",
            sizeBytes = 1024,
            status = "APPROVED",
            folder = "heroes"
        )

        assertEquals("abc-123", obj["assetId"]!!.jsonPrimitive.content)
        assertEquals("test.png", obj["fileName"]!!.jsonPrimitive.content)
        assertEquals("/tmp/test.png", obj["filePath"]!!.jsonPrimitive.content)
        assertEquals("SPRITE", obj["type"]!!.jsonPrimitive.content)
        assertEquals("png", obj["format"]!!.jsonPrimitive.content)
        assertEquals("A test sprite", obj["description"]!!.jsonPrimitive.content)
        assertEquals(1024L, obj["sizeBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals("APPROVED", obj["status"]!!.jsonPrimitive.content)
        assertEquals("heroes", obj["folder"]!!.jsonPrimitive.content)
    }
}
