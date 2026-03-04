package dev.gameharness.api.meshy

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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MeshyClientTest {

    private fun loadResource(name: String): String =
        this::class.java.classLoader.getResource(name)!!.readText()

    private fun jsonHeaders() =
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `polls until task succeeds`() = runTest {
        var pollCount = 0
        val mockClient = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            when {
                // Create task (POST)
                request.method == HttpMethod.Post -> respond(
                    content = loadResource("meshy-create-response.json"),
                    headers = jsonHeaders()
                )
                // Download model file
                url.contains("assets.meshy.ai") -> respond(
                    content = byteArrayOf(0x67, 0x6C, 0x54, 0x46), // glTF magic bytes
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                )
                // Poll task status (GET)
                else -> {
                    pollCount++
                    if (pollCount <= 1) {
                        respond(content = loadResource("meshy-task-pending.json"), headers = jsonHeaders())
                    } else {
                        respond(content = loadResource("meshy-task-succeeded.json"), headers = jsonHeaders())
                    }
                }
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }

        val client = MeshyClient(
            apiKey = "test-key",
            httpClient = mockClient,
            pollInterval = 10.milliseconds,
            maxPollDuration = 5000.milliseconds
        )
        val request = GenerationRequest(
            description = "a fantasy sword",
            type = AssetType.MODEL_3D
        )

        val result = client.generate(request)
        assertIs<GenerationResult.Completed>(result)
        assertTrue(result.asset.format == "glb")
        assertTrue(result.fileBytes.isNotEmpty())
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

        val client = MeshyClient(apiKey = "bad-key", httpClient = mockClient)
        val request = GenerationRequest(description = "a sword", type = AssetType.MODEL_3D)

        assertFailsWith<ApiException.AuthenticationFailed> {
            client.generate(request)
        }
    }

    @Test
    fun `throws on task failure`() = runTest {
        val mockClient = HttpClient(MockEngine { request ->
            when {
                request.method == HttpMethod.Post -> respond(
                    content = loadResource("meshy-create-response.json"),
                    headers = jsonHeaders()
                )
                else -> respond(
                    content = """{"id":"task-123","status":"FAILED","task_error":{"message":"Generation failed"}}""",
                    headers = jsonHeaders()
                )
            }
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }

        val client = MeshyClient(
            apiKey = "test-key",
            httpClient = mockClient,
            pollInterval = 10.milliseconds
        )
        val request = GenerationRequest(description = "a sword", type = AssetType.MODEL_3D)

        assertFailsWith<ApiException.GenerationFailed> {
            client.generate(request)
        }
    }
}
