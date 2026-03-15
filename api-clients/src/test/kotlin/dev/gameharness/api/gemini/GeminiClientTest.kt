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
import kotlin.test.assertNull
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

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    @Test
    fun `sends imageConfig with imageSize and aspectRatio`() = runTest {
        var capturedBody: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(
            description = "a coin",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit", "aspectRatio" to "16:9", "imageSize" to "1K")
        ))

        val body = assertNotNull(capturedBody)
        val parsed = testJson.decodeFromString(GeminiRequest.serializer(), body)
        val imageConfig = assertNotNull(parsed.generationConfig?.imageConfig)
        assertEquals("1K", imageConfig.imageSize)
        assertEquals("16:9", imageConfig.aspectRatio)
    }

    @Test
    fun `includes dimension hints in prompt text`() = runTest {
        var capturedBody: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(
            description = "a tile",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit", "width" to "64", "height" to "64")
        ))

        val body = assertNotNull(capturedBody)
        val parsed = testJson.decodeFromString(GeminiRequest.serializer(), body)
        val textPart = parsed.contents.first().parts.find { it.text != null }
        assertNotNull(textPart)
        assertTrue(textPart.text!!.contains("64x64 pixels"))
    }

    @Test
    fun `prompt requests green background by default`() = runTest {
        var capturedBody: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(
            description = "a shield",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit")
        ))

        val body = assertNotNull(capturedBody)
        val parsed = testJson.decodeFromString(GeminiRequest.serializer(), body)
        val textPart = parsed.contents.first().parts.find { it.text != null }
        assertNotNull(textPart)
        assertTrue(textPart.text!!.contains("#00b140"), "Prompt should request green background")
        assertTrue(textPart.text!!.contains("green"), "Prompt should mention green background")
        assertTrue(textPart.text!!.contains("chroma key"), "Prompt should mention chroma key concept")
    }

    @Test
    fun `prompt requests transparent background when removeBg is false`() = runTest {
        var capturedBody: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(
            description = "a potion",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit", "removeBg" to "false")
        ))

        val body = assertNotNull(capturedBody)
        val parsed = testJson.decodeFromString(GeminiRequest.serializer(), body)
        val textPart = parsed.contents.first().parts.find { it.text != null }
        assertNotNull(textPart)
        assertTrue(textPart.text!!.contains("Transparent background"), "Prompt should request transparent background")
    }

    @Test
    fun `builds imageConfig with only aspectRatio when no imageSize`() = runTest {
        var capturedBody: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(
            description = "a sword",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit", "aspectRatio" to "3:2")
        ))

        val body = assertNotNull(capturedBody)
        val parsed = testJson.decodeFromString(GeminiRequest.serializer(), body)
        val imageConfig = assertNotNull(parsed.generationConfig?.imageConfig)
        assertEquals("3:2", imageConfig.aspectRatio)
        assertNull(imageConfig.imageSize)
    }

    // -- selectChromaKeyColor() -----------------------------------------------

    @Test
    fun `selectChromaKeyColor returns green for neutral description`() {
        val result = GeminiClient.selectChromaKeyColor("a medieval sword")
        assertEquals(GeminiClient.CHROMA_GREEN, result)
    }

    @Test
    fun `selectChromaKeyColor returns magenta for green-related description`() {
        val result = GeminiClient.selectChromaKeyColor("a green slime monster")
        assertEquals(GeminiClient.CHROMA_MAGENTA, result)
    }

    @Test
    fun `selectChromaKeyColor returns magenta for nature keywords`() {
        val result = GeminiClient.selectChromaKeyColor("a tall oak tree in a forest")
        assertEquals(GeminiClient.CHROMA_MAGENTA, result)
    }

    @Test
    fun `selectChromaKeyColor returns blue when both green and magenta conflict`() {
        val result = GeminiClient.selectChromaKeyColor("a purple dragon in a green forest")
        assertEquals(GeminiClient.CHROMA_BLUE, result)
    }

    @Test
    fun `selectChromaKeyColor is case insensitive`() {
        val result = GeminiClient.selectChromaKeyColor("A GREEN SLIME MONSTER")
        assertEquals(GeminiClient.CHROMA_MAGENTA, result)
    }

    @Test
    fun `prompt uses magenta for green-themed sprite description`() = runTest {
        var capturedBody: String? = null
        val client = GeminiClient(
            apiKey = "test-key",
            httpClient = mockHttpClient { request ->
                capturedBody = String(request.body.toByteArray())
                respond(content = loadResource("gemini-success-response.json"), headers = jsonHeaders())
            }
        )

        client.generate(GenerationRequest(
            description = "a green slime monster",
            type = AssetType.SPRITE,
            params = mapOf("style" to "16bit")
        ))

        val body = assertNotNull(capturedBody)
        val parsed = testJson.decodeFromString(GeminiRequest.serializer(), body)
        val textPart = parsed.contents.first().parts.find { it.text != null }
        assertNotNull(textPart)
        assertTrue(textPart.text!!.contains("#ff00ff"), "Prompt should use magenta for green sprite")
        assertTrue(textPart.text!!.contains("magenta"), "Prompt should mention magenta background")
    }
}
