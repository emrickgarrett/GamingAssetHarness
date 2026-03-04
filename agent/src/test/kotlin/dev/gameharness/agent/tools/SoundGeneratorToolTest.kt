package dev.gameharness.agent.tools

import dev.gameharness.api.elevenlabs.ElevenLabsClient
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

class SoundGeneratorToolTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var workspace: Workspace

    @BeforeEach
    fun setup() {
        workspaceManager = WorkspaceManager(tempDir.resolve("registry"))
        workspace = workspaceManager.createWorkspace("TestProject", tempDir.resolve("ws/TestProject"))
    }

    @Test
    fun `rejects blank description`() {
        assertFailsWith<IllegalArgumentException> {
            SoundGeneratorTool.Args(description = "")
        }
    }

    @Test
    fun `rejects duration out of range`() {
        assertFailsWith<IllegalArgumentException> {
            SoundGeneratorTool.Args(description = "explosion", durationSeconds = 0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            SoundGeneratorTool.Args(description = "explosion", durationSeconds = 30.0)
        }
    }

    @Test
    fun `accepts valid args with defaults`() {
        val args = SoundGeneratorTool.Args(description = "explosion")
        assertEquals(2.0, args.durationSeconds)
    }

    @Test
    fun `generates sound and saves to workspace`() = runTest {
        val fakeAudioBytes = ByteArray(512) { it.toByte() }
        val httpClient = HttpClient(MockEngine {
            respond(
                content = fakeAudioBytes,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true })
            }
        }

        val elevenLabsClient = ElevenLabsClient(apiKey = "test-key", httpClient = httpClient)
        var updatedWorkspace: Workspace? = null
        val tool = SoundGeneratorTool(elevenLabsClient, workspaceManager, { workspace }) { updatedWorkspace = it }
        val result = tool.execute(SoundGeneratorTool.Args(description = "explosion", durationSeconds = 1.5))

        assertTrue(result.contains("Successfully generated sound effect"))
        assertNotNull(updatedWorkspace)
        assertTrue(updatedWorkspace!!.assets.isNotEmpty())
        assertEquals(AssetType.SOUND_EFFECT, updatedWorkspace!!.assets.first().type)
    }
}
