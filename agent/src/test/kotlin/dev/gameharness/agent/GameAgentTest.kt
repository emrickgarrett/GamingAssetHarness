package dev.gameharness.agent

import dev.gameharness.agent.bridge.AgentBridgeImpl
import dev.gameharness.core.config.AppConfig
import dev.gameharness.core.model.ChatRole
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GameAgentTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var bridge: AgentBridgeImpl
    private lateinit var workspaceManager: WorkspaceManager

    private val testConfig = AppConfig.fromEnvironment { key ->
        mapOf(
            "OPENROUTER_API_KEY" to "test-openrouter-key",
            "GEMINI_API_KEY" to "test-gemini-key",
            "MESHY_API_KEY" to "test-meshy-key",
            "SUNO_API_KEY" to "test-suno-key",
            "ELEVENLABS_API_KEY" to "test-elevenlabs-key"
        )[key]
    }

    private val minimalConfig = AppConfig(
        openRouterApiKey = "test-openrouter-key"
    )

    @BeforeEach
    fun setup() {
        bridge = AgentBridgeImpl()
        workspaceManager = WorkspaceManager(tempDir.resolve("registry"))
    }

    @Test
    fun `GameAgent can be constructed`() {
        val agent = GameAgent(testConfig, workspaceManager, bridge)
        assertNotNull(agent)
    }

    @Test
    fun `setWorkspace updates current workspace`() {
        val agent = GameAgent(testConfig, workspaceManager, bridge)
        val workspace = workspaceManager.createWorkspace("TestProject", tempDir.resolve("ws/TestProject"))
        agent.setWorkspace(workspace)
        // No assertion on private field, but verifying no exception is thrown
    }

    @Test
    fun `buildAgentInput returns raw message when no history`() {
        val agent = GameAgent(testConfig, workspaceManager, bridge)
        val result = agent.buildAgentInput("Generate a sword sprite")
        assertEquals("Generate a sword sprite", result)
    }

    @Test
    fun `buildAgentInput includes conversation history`() {
        val agent = GameAgent(testConfig, workspaceManager, bridge)

        // Simulate a real conversation flow:
        bridge.sendAgentMessage("Hello! I'm your Game Asset Generator.")
        bridge.submitUserInput("Make me a sword")
        bridge.sendAgentMessage("Sure, generating a sword sprite...")
        bridge.submitUserInput("Make it bigger")

        val result = agent.buildAgentInput("Make it bigger")

        assertTrue(result.contains("Previous conversation:"))
        assertTrue(result.contains("Assistant: Hello! I'm your Game Asset Generator."))
        assertTrue(result.contains("User: Make me a sword"))
        assertTrue(result.contains("Assistant: Sure, generating a sword sprite..."))
        assertTrue(result.contains("User: Make it bigger"))
    }

    @Test
    fun `buildAgentInput drops latest message to avoid duplication`() {
        val agent = GameAgent(testConfig, workspaceManager, bridge)

        bridge.sendAgentMessage("Hello!")
        bridge.submitUserInput("First message")

        val result = agent.buildAgentInput("First message")

        assertTrue(result.contains("Previous conversation:"))
        assertTrue(result.contains("Assistant: Hello!"))
        assertTrue(result.contains("User: First message"))
    }

    @Test
    fun `stop cancels agent job`() {
        val agent = GameAgent(testConfig, workspaceManager, bridge)
        // stop() should not throw even when not started
        agent.stop()
    }

    @Test
    fun `start sends greeting message`() = runTest {
        val agent = GameAgent(testConfig, workspaceManager, bridge)
        val workspace = workspaceManager.createWorkspace("TestProject", tempDir.resolve("ws/TestProject"))
        agent.setWorkspace(workspace)

        agent.start(this)
        advanceUntilIdle()

        val messages = bridge.conversationState.value.messages
        assertTrue(messages.isNotEmpty())
        assertEquals(ChatRole.ASSISTANT, messages[0].role)
        assertTrue(messages[0].content.contains("Game Asset Generator"))

        agent.stop()
    }

    @Test
    fun `start then stop lifecycle works cleanly`() = runTest {
        val agent = GameAgent(testConfig, workspaceManager, bridge)
        val workspace = workspaceManager.createWorkspace("TestProject", tempDir.resolve("ws/TestProject"))
        agent.setWorkspace(workspace)

        agent.start(this)
        advanceUntilIdle()

        agent.stop()
        advanceUntilIdle()

        // Verify greeting was sent
        val messages = bridge.conversationState.value.messages
        assertTrue(messages.isNotEmpty())
    }

    // --- Greeting tests ---

    @Test
    fun `buildGreeting with all keys lists all capabilities`() {
        val greeting = GameAgent.buildGreeting(testConfig)
        assertTrue(greeting.contains("2D sprites"))
        assertTrue(greeting.contains("3D models"))
        assertTrue(greeting.contains("music"))
        assertTrue(greeting.contains("sound effects"))
    }

    @Test
    fun `buildGreeting with no optional keys suggests settings`() {
        val greeting = GameAgent.buildGreeting(minimalConfig)
        assertTrue(greeting.contains("Settings"))
        assertTrue(greeting.contains("Gemini"))
        assertTrue(greeting.contains("Meshy"))
        assertTrue(greeting.contains("Suno"))
        assertTrue(greeting.contains("ElevenLabs"))
    }

    @Test
    fun `buildGreeting with partial keys lists only configured capabilities`() {
        val partialConfig = AppConfig(
            openRouterApiKey = "or",
            geminiApiKey = "gem",
            meshyApiKey = "mesh"
        )
        val greeting = GameAgent.buildGreeting(partialConfig)
        assertTrue(greeting.contains("2D sprites"))
        assertTrue(greeting.contains("3D models"))
        assertFalse(greeting.contains("music"))
        assertFalse(greeting.contains("sound effects"))
    }

    // --- Error message formatting tests ---

    @Test
    fun `formatErrorMessage for network error`() {
        val msg = GameAgent.formatErrorMessage(java.net.UnknownHostException("api.openrouter.ai"))
        assertTrue(msg.contains("internet connection"))
    }

    @Test
    fun `formatErrorMessage for 401 unauthorized`() {
        val msg = GameAgent.formatErrorMessage(RuntimeException("HTTP 401 Unauthorized"))
        assertTrue(msg.contains("API key"))
        assertTrue(msg.contains("Settings"))
    }

    @Test
    fun `formatErrorMessage for rate limit`() {
        val msg = GameAgent.formatErrorMessage(RuntimeException("429 Too Many Requests"))
        assertTrue(msg.contains("rate limit"))
    }

    @Test
    fun `formatErrorMessage for timeout`() {
        val msg = GameAgent.formatErrorMessage(java.net.SocketTimeoutException("Read timed out"))
        assertTrue(msg.contains("timed out"))
    }

    @Test
    fun `formatErrorMessage for workspace error`() {
        val msg = GameAgent.formatErrorMessage(IllegalStateException("No workspace selected"))
        assertTrue(msg.contains("workspace"))
    }

    @Test
    fun `formatErrorMessage for generic error`() {
        val msg = GameAgent.formatErrorMessage(RuntimeException("Something unknown happened"))
        assertTrue(msg.contains("Something unknown happened"))
    }
}
