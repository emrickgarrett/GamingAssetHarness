package dev.gameharness.api.suno

import dev.gameharness.api.common.ApiException
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GenerationRequest
import dev.gameharness.core.model.GenerationResult
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SunoClientTest {

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `generates music successfully with polling`() = runTest {
        var pollCount = 0
        val fakeAudioBytes = ByteArray(2048) { it.toByte() }

        val mockClient = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                // Create generation
                url.contains("/api/generate") -> respond(
                    content = """{"id":"gen-123","clips":[{"id":"clip-456","status":"queued"}]}""",
                    headers = jsonHeaders()
                )
                // Poll status
                url.contains("/api/get") -> {
                    pollCount++
                    if (pollCount <= 1) {
                        respond(
                            content = """[{"id":"clip-456","status":"streaming"}]""",
                            headers = jsonHeaders()
                        )
                    } else {
                        respond(
                            content = """[{"id":"clip-456","status":"complete","audio_url":"https://cdn.suno.ai/test.mp3","duration":30.0}]""",
                            headers = jsonHeaders()
                        )
                    }
                }
                // Download audio
                url.contains("cdn.suno.ai") -> respond(
                    content = fakeAudioBytes,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                )
                else -> respond(
                    content = "Not found",
                    status = HttpStatusCode.NotFound
                )
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }

        val client = SunoClient(
            apiKey = "test-key",
            baseUrl = "https://test-suno.local",
            httpClient = mockClient,
            pollInterval = 10.milliseconds,
            maxPollDuration = 5000.milliseconds
        )

        val request = GenerationRequest(
            description = "epic battle theme",
            type = AssetType.MUSIC,
            params = mapOf("genre" to "orchestral", "mood" to "intense")
        )

        val result = client.generate(request)
        assertIs<GenerationResult.Completed>(result)
        assertEquals(AssetType.MUSIC, result.asset.type)
        assertEquals("mp3", result.asset.format)
        assertTrue(result.fileBytes.isNotEmpty())
        assertTrue(pollCount >= 2)
    }

    @Test
    fun `throws on authentication failure`() = runTest {
        val mockClient = HttpClient(MockEngine {
            respond(
                content = """{"error":"Unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = jsonHeaders()
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }

        val client = SunoClient(
            apiKey = "bad-key",
            baseUrl = "https://test-suno.local",
            httpClient = mockClient
        )
        val request = GenerationRequest(description = "music", type = AssetType.MUSIC)

        assertFailsWith<ApiException.AuthenticationFailed> {
            client.generate(request)
        }
    }

    @Test
    fun `throws on generation error during polling`() = runTest {
        val mockClient = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("/api/generate") -> respond(
                    content = """{"clips":[{"id":"clip-789","status":"queued"}]}""",
                    headers = jsonHeaders()
                )
                url.contains("/api/get") -> respond(
                    content = """[{"id":"clip-789","status":"error"}]""",
                    headers = jsonHeaders()
                )
                else -> respond(content = "Not found", status = HttpStatusCode.NotFound)
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }

        val client = SunoClient(
            apiKey = "test-key",
            baseUrl = "https://test-suno.local",
            httpClient = mockClient,
            pollInterval = 10.milliseconds
        )
        val request = GenerationRequest(description = "music", type = AssetType.MUSIC)

        assertFailsWith<ApiException.GenerationFailed> {
            client.generate(request)
        }
    }
}
