package dev.gameharness.api.gemini

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiClientTest {

    private fun loadResource(name: String): String =
        this::class.java.classLoader.getResource(name)!!.readText()

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
    fun `generates sprite from text prompt`() = runTest {
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                assertEquals("test-key", request.headers["x-goog-api-key"])
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        val result = client.generate(
            GenerationRequest(description = "a medieval sword", type = AssetType.SPRITE, params = mapOf("style" to "16bit"))
        )

        assertIs<GenerationResult.Completed>(result)
        assertEquals(AssetType.SPRITE, result.asset.type)
        assertEquals("png", result.asset.format)
        assertTrue(result.fileBytes.isNotEmpty())
    }

    @Test
    fun `throws on authentication failure`() = runTest {
        val client = GeminiClient(
            apiKey = "bad-key",
            httpClient = mockHttpClient {
                respond(content = """{"error":{"code":401}}""", status = HttpStatusCode.Unauthorized, headers = jsonHeaders())
            }
        )

        assertFailsWith<ApiException.AuthenticationFailed> {
            client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))
        }
    }

    @Test
    fun `throws on rate limit`() = runTest {
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient {
                respond(content = """{"error":{"code":429}}""", status = HttpStatusCode.TooManyRequests, headers = jsonHeaders())
            }
        )

        assertFailsWith<ApiException.RateLimited> {
            client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))
        }
    }

    @Test
    fun `returns failed on error response`() = runTest {
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient {
                respond(content = loadResource("gemini-error-response.json"), status = HttpStatusCode.BadRequest, headers = jsonHeaders())
            }
        )

        val result = client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))
        assertIs<GenerationResult.Failed>(result)
        assertTrue(result.error.contains("Invalid request"))
    }

    @Test
    fun `returns failed on network error`() = runTest {
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { throw java.io.IOException("Connection refused") }
        )

        val result = client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))
        assertIs<GenerationResult.Failed>(result)
        assertTrue(result.retryable)
    }

    @Test
    fun `handles response with no image data`() = runTest {
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient {
                respond(
                    content = """{"candidates":[{"content":{"parts":[{"text":"Cannot generate"}]},"finishReason":"SAFETY"}]}""",
                    headers = jsonHeaders()
                )
            }
        )

        val result = client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))
        assertIs<GenerationResult.Failed>(result)
    }

    @Test
    fun `uses configured model in endpoint URL`() = runTest {
        var capturedUrl: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            model = "gemini-3.1-flash-image-preview",
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))

        val url = assertNotNull(capturedUrl)
        assertTrue(url.contains("models/gemini-3.1-flash-image-preview:generateContent"))
    }

    @Test
    fun `uses default model when none specified`() = runTest {
        var capturedUrl: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedUrl = request.url.toString()
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(description = "a sword", type = AssetType.SPRITE))

        val url = assertNotNull(capturedUrl)
        assertTrue(url.contains("models/gemini-2.5-flash-image:generateContent"))
    }
}
