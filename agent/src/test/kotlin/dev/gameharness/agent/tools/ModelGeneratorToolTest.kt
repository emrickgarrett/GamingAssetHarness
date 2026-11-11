package dev.gameharness.agent.tools

import dev.gameharness.agent.bridge.GenerationProgress
import dev.gameharness.api.common.ApiException
import dev.gameharness.api.meshy.MeshyClient
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ModelGeneratorToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var workspace: Workspace

    @BeforeEach
    fun setup() {
        workspaceManager = WorkspaceManager(tempDir.resolve("registry"))
        workspace = workspaceManager.createWorkspace("TestProject", tempDir.resolve("ws/TestProject"))
    }

    private fun createMeshyClient(handler: MockRequestHandler): MeshyClient {
        val httpClient = HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }
        return MeshyClient(
            apiKey = "test-key",
            httpClient = httpClient,
            pollInterval = 10.milliseconds,
            maxPollDuration = 5.seconds
        )
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun successMockHandler(): MockRequestHandler {
        var getPollCount = 0
        return { request ->
            val url = request.url.toString()
            when {
                request.method == HttpMethod.Post -> respond(
                    content = """{"result": "task-123"}""",
                    headers = jsonHeaders()
                )
                url.contains("assets.meshy.ai") -> respond(
                    content = ByteArray(256) { it.toByte() },
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                )
                else -> {
                    getPollCount++
                    if (getPollCount % 2 == 1) {
                        respond(
                            content = """{"id":"task-123","status":"IN_PROGRESS","progress":50}""",
                            headers = jsonHeaders()
                        )
                    } else {
                        respond(
                            content = """{
                                "id":"task-123",
                                "status":"SUCCEEDED",
                                "progress":100,
                                "model_urls":{"glb":"https://assets.meshy.ai/model.glb","fbx":"https://assets.meshy.ai/model.fbx","obj":"https://assets.meshy.ai/model.obj"}
                            }""",
                            headers = jsonHeaders()
                        )
                    }
                }
            }
        }
    }

    // --- Args validation ---

    @Test
    fun `rejects blank description`() {
        assertFailsWith<IllegalArgumentException> {
            ModelGeneratorTool.Args(description = "")
        }
    }

    @Test
    fun `accepts valid args with defaults`() {
        val args = ModelGeneratorTool.Args(description = "a fantasy sword")
        assertEquals("a fantasy sword", args.description)
        assertEquals("realistic", args.artStyle)
    }

    @Test
    fun `accepts all valid art styles`() {
        for (style in listOf("realistic", "cartoon", "low-poly", "sculpture")) {
            val args = ModelGeneratorTool.Args(description = "test model", artStyle = style)
            assertEquals(style, args.artStyle)
        }
    }

    @Test
    fun `rejects invalid format`() {
        assertFailsWith<IllegalArgumentException> {
            ModelGeneratorTool.Args(description = "a sword", format = "stl")
        }
    }

    @Test
    fun `accepts fbx format`() {
        val args = ModelGeneratorTool.Args(description = "a shield", format = "fbx")
        assertEquals("fbx", args.format)
    }

    @Test
    fun `accepts imageUrl arg`() {
        val args = ModelGeneratorTool.Args(
            description = "3D model from image",
            imageUrl = "https://example.com/concept.png"
        )
        assertEquals("https://example.com/concept.png", args.imageUrl)
    }

    // --- Execution tests ---

    @Test
    fun `generates model and saves to workspace`() = runTest {
        val meshyClient = createMeshyClient(successMockHandler())

        var updatedWorkspace: Workspace? = null
        val tool = ModelGeneratorTool(meshyClient, workspaceManager, { workspace }) { updatedWorkspace = it }
        val result = tool.execute(ModelGeneratorTool.Args(description = "a fantasy sword"))

        assertTrue(result.contains("Successfully generated 3D model"))
        assertTrue(result.contains("Asset ID:"))
        assertNotNull(updatedWorkspace)
        assertTrue(updatedWorkspace!!.assets.isNotEmpty())
        assertEquals(AssetType.MODEL_3D, updatedWorkspace!!.assets.first().type)
    }

    @Test
    fun `returns error message on failure`() = runTest {
        var getPollCount = 0
        val meshyClient = createMeshyClient { request ->
            when {
                request.method == HttpMethod.Post -> respond(
                    content = """{"result": "task-fail"}""",
                    headers = jsonHeaders()
                )
                else -> {
                    getPollCount++
                    if (getPollCount <= 2) {
                        respond(
                            content = """{"id":"task-fail","status":"IN_PROGRESS","progress":30}""",
                            headers = jsonHeaders()
                        )
                    } else {
                        respond(
                            content = """{"id":"task-fail","status":"FAILED","progress":0,"task_error":{"message":"Mesh generation failed"}}""",
                            headers = jsonHeaders()
                        )
                    }
                }
            }
        }

        val tool = ModelGeneratorTool(meshyClient, workspaceManager, { workspace })
        val result = tool.execute(ModelGeneratorTool.Args(description = "a broken model"))

        assertTrue(result.contains("Failed to generate 3D model"))
    }

    @Test
    fun `handles authentication failure`() = runTest {
        val meshyClient = createMeshyClient { request ->
            respond(content = "", status = HttpStatusCode.Unauthorized)
        }

        val tool = ModelGeneratorTool(meshyClient, workspaceManager, { workspace })
        val result = tool.execute(ModelGeneratorTool.Args(description = "a sword"))

        assertTrue(result.contains("API key"))
    }

    @Test
    fun `handles rate limit`() = runTest {
        val meshyClient = createMeshyClient { request ->
            respond(content = "", status = HttpStatusCode.TooManyRequests)
        }

        val tool = ModelGeneratorTool(meshyClient, workspaceManager, { workspace })
        val result = tool.execute(ModelGeneratorTool.Args(description = "a sword"))

        assertTrue(result.contains("rate limit"))
    }

    @Test
    fun `forwards progress updates`() = runTest {
        val meshyClient = createMeshyClient(successMockHandler())

        val progressUpdates = mutableListOf<GenerationProgress>()
        val tool = ModelGeneratorTool(
            meshyClient, workspaceManager, { workspace },
            onProgress = { progress -> if (progress != null) progressUpdates.add(progress) }
        )
        tool.execute(ModelGeneratorTool.Args(description = "a sword"))

        assertTrue(progressUpdates.isNotEmpty())
        assertTrue(progressUpdates.all { it.assetType == AssetType.MODEL_3D })
    }

    @Test
    fun `clears progress on completion`() = runTest {
        val meshyClient = createMeshyClient(successMockHandler())

        var clearedProgress = false
        val tool = ModelGeneratorTool(
            meshyClient, workspaceManager, { workspace },
            onProgress = { progress -> if (progress == null) clearedProgress = true }
        )
        tool.execute(ModelGeneratorTool.Args(description = "a sword"))

        assertTrue(clearedProgress)
    }
}
