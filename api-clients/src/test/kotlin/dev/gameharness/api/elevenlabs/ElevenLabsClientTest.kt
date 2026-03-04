package dev.gameharness.api.elevenlabs

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

class ElevenLabsClientTest {

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun mockHttpClient(handler: MockRequestHandler): HttpClient {
        return HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }
    }

    @Test
    fun `generates sound effect successfully`() = runTest {
        val fakeAudioBytes = ByteArray(1024) { it.toByte() }

        val client = ElevenLabsClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                assertEquals("test-key", request.headers["xi-api-key"])
                respond(
                    content = fakeAudioBytes,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                )
            }
        )

        val result = client.generate(
            GenerationRequest(description = "explosion sound", type = AssetType.SOUND_EFFECT, params = mapOf("duration" to "2.0"))
        )

        assertIs<GenerationResult.Completed>(result)
        assertEquals(AssetType.SOUND_EFFECT, result.asset.type)
        assertEquals("mp3", result.asset.format)
        assertEquals(1024L, result.asset.sizeBytes)
    }

    @Test
    fun `throws on authentication failure`() = runTest {
        val client = ElevenLabsClient(
            apiKey = "bad-key",
            httpClient = mockHttpClient {
                respond(content = """{"error":"Unauthorized"}""", status = HttpStatusCode.Unauthorized, headers = jsonHeaders())
            }
        )

        assertFailsWith<ApiException.AuthenticationFailed> {
            client.generate(GenerationRequest(description = "boom", type = AssetType.SOUND_EFFECT))
        }
    }

    @Test
    fun `throws on rate limit`() = runTest {
        val client = ElevenLabsClient(
            apiKey = "test-key",
            httpClient = mockHttpClient {
                respond(content = """{"error":"Rate limited"}""", status = HttpStatusCode.TooManyRequests, headers = jsonHeaders())
            }
        )

        assertFailsWith<ApiException.RateLimited> {
            client.generate(GenerationRequest(description = "boom", type = AssetType.SOUND_EFFECT))
        }
    }

    @Test
    fun `returns failed on server error`() = runTest {
        val client = ElevenLabsClient(
            apiKey = "test-key",
            httpClient = mockHttpClient {
                respond(content = """{"error":"Internal error"}""", status = HttpStatusCode.InternalServerError, headers = jsonHeaders())
            }
        )

        val result = client.generate(GenerationRequest(description = "boom", type = AssetType.SOUND_EFFECT))
        assertIs<GenerationResult.Failed>(result)
        assertTrue(result.retryable)
    }

    @Test
    fun `returns failed on network error`() = runTest {
        val client = ElevenLabsClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { throw java.io.IOException("Connection refused") }
        )

        val result = client.generate(GenerationRequest(description = "boom", type = AssetType.SOUND_EFFECT))
        assertIs<GenerationResult.Failed>(result)
        assertTrue(result.retryable)
    }
}
