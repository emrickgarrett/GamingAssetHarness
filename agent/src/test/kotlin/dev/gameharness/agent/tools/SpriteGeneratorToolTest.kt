package dev.gameharness.agent.tools

import dev.gameharness.agent.bridge.AgentBridgeImpl
import dev.gameharness.api.common.AssetGenerationClient
import dev.gameharness.api.gemini.GeminiClient
import dev.gameharness.core.model.*
import dev.gameharness.core.workspace.WorkspaceManager
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SpriteGeneratorToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var workspace: Workspace
    private lateinit var bridge: AgentBridgeImpl

    @BeforeEach
    fun setup() {
        workspaceManager = WorkspaceManager(tempDir.resolve("registry"))
        workspace = workspaceManager.createWorkspace("TestProject", tempDir.resolve("ws/TestProject"))
        bridge = AgentBridgeImpl()
    }

    private fun createGeminiClient(handler: MockRequestHandler): GeminiClient {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }
        return GeminiClient(apiKey = "test-key", httpClient = httpClient)
    }

    @Test
    fun `rejects blank description`() {
        assertFailsWith<IllegalArgumentException> {
            SpriteGeneratorTool.Args(description = "", style = "16bit")
        }
    }

    @Test
    fun `rejects invalid style`() {
        assertFailsWith<IllegalArgumentException> {
            SpriteGeneratorTool.Args(description = "a sword", style = "invalid")
        }
    }

    @Test
    fun `accepts valid args`() {
        val args = SpriteGeneratorTool.Args(
            description = "a medieval sword",
            style = "16bit",
            aspectRatio = "1:1"
        )
        assertEquals("a medieval sword", args.description)
        assertEquals("16bit", args.style)
    }

    @Test
    fun `generates sprite and saves to workspace`() = runTest {
        val geminiClient = createGeminiClient {
            respond(
                content = """{
                    "candidates": [{
                        "content": {
                            "parts": [{
                                "inlineData": {
                                    "mimeType": "image/png",
                                    "data": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                                }
                            }]
                        },
                        "finishReason": "STOP"
                    }]
                }""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        var updatedWorkspace: Workspace? = null
        val tool = SpriteGeneratorTool(geminiClient, workspaceManager, { workspace }, bridge) { updatedWorkspace = it }
        val result = tool.execute(SpriteGeneratorTool.Args(description = "a sword", style = "16bit"))

        assertTrue(result.contains("Successfully generated sprite"))
        assertTrue(result.contains("Asset ID:"))
        assertNotNull(updatedWorkspace)
        assertTrue(updatedWorkspace!!.assets.isNotEmpty())
    }

    @Test
    fun `returns error message on failure`() = runTest {
        val geminiClient = createGeminiClient {
            respond(
                content = """{"candidates":[{"content":{"parts":[{"text":"Cannot generate"}]}}]}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val tool = SpriteGeneratorTool(geminiClient, workspaceManager, { workspace }, bridge)
        val result = tool.execute(SpriteGeneratorTool.Args(description = "a sword", style = "16bit"))

        assertTrue(result.contains("Failed to generate sprite"))
    }
}
