package dev.gameharness.agent.bridge

import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetReviewDecision
import dev.gameharness.core.model.ChatRole
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import kotlin.test.*

class AgentBridgeImplTest {

    @Test
    fun `sendAgentMessage adds assistant message to state`() {
        val bridge = AgentBridgeImpl()
        bridge.sendAgentMessage("Hello!")

        val state = bridge.conversationState.value
        assertEquals(1, state.messages.size)
        assertEquals(ChatRole.ASSISTANT, state.messages[0].role)
        assertEquals("Hello!", state.messages[0].content)
    }

    @Test
    fun `submitUserInput adds user message and sets thinking`() {
        val bridge = AgentBridgeImpl()
        bridge.submitUserInput("Generate a sword")

        val state = bridge.conversationState.value
        assertEquals(1, state.messages.size)
        assertEquals(ChatRole.USER, state.messages[0].role)
        assertTrue(state.isAgentThinking)
    }

    @Test
    fun `awaitUserInput suspends until submitUserInput is called`() = runTest {
        val bridge = AgentBridgeImpl()

        val deferred = async { bridge.awaitUserInput() }
        advanceUntilIdle()

        assertFalse(deferred.isCompleted)

        bridge.submitUserInput("Hello agent")
        advanceUntilIdle()

        assertTrue(deferred.isCompleted)
        assertEquals("Hello agent", deferred.await())
    }

    @Test
    fun `presentAssetForReview suspends until decision is submitted`() = runTest {
        val bridge = AgentBridgeImpl()

        val deferred = async {
            bridge.presentAssetForReview("asset-123", "A cool sword sprite")
        }
        advanceUntilIdle()

        assertFalse(deferred.isCompleted)

        // State should show the preview
        val previewState = bridge.conversationState.value
        assertNotNull(previewState.currentAssetPreview)
        assertEquals("asset-123", previewState.currentAssetPreview?.assetId)

        // Submit decision
        bridge.submitAssetDecision(AssetReviewDecision(AssetDecision.APPROVED, null))
        advanceUntilIdle()

        assertTrue(deferred.isCompleted)
        assertEquals(AssetDecision.APPROVED, deferred.await().decision)

        // Preview should be cleared
        assertNull(bridge.conversationState.value.currentAssetPreview)
    }

    @Test
    fun `presentAssetForReview returns feedback on deny`() = runTest {
        val bridge = AgentBridgeImpl()

        val deferred = async {
            bridge.presentAssetForReview("asset-456", "Battle music")
        }
        advanceUntilIdle()

        bridge.submitAssetDecision(
            AssetReviewDecision(AssetDecision.DENIED, "Too fast, make it slower")
        )
        advanceUntilIdle()

        val result = deferred.await()
        assertEquals(AssetDecision.DENIED, result.decision)
        assertEquals("Too fast, make it slower", result.feedback)
    }

    @Test
    fun `updateThinkingState changes state`() {
        val bridge = AgentBridgeImpl()

        assertFalse(bridge.conversationState.value.isAgentThinking)
        bridge.updateThinkingState(true)
        assertTrue(bridge.conversationState.value.isAgentThinking)
        bridge.updateThinkingState(false)
        assertFalse(bridge.conversationState.value.isAgentThinking)
    }

    @Test
    fun `messages accumulate in order`() {
        val bridge = AgentBridgeImpl()
        bridge.submitUserInput("Hello")
        bridge.sendAgentMessage("Hi there!")
        bridge.submitUserInput("Generate a sprite")
        bridge.sendAgentMessage("Sure, generating...")

        val messages = bridge.conversationState.value.messages
        assertEquals(4, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals(ChatRole.ASSISTANT, messages[1].role)
        assertEquals(ChatRole.USER, messages[2].role)
        assertEquals(ChatRole.ASSISTANT, messages[3].role)
    }

    @Test
    fun `reset clears all state`() {
        val bridge = AgentBridgeImpl()
        bridge.submitUserInput("Hello")
        bridge.sendAgentMessage("Hi there!")
        bridge.updateThinkingState(true)
        bridge.updateProgress(GenerationProgress(dev.gameharness.core.model.AssetType.SPRITE, 50, "Generating..."))

        // Verify state is populated
        assertTrue(bridge.conversationState.value.messages.isNotEmpty())
        assertTrue(bridge.conversationState.value.isAgentThinking)
        assertNotNull(bridge.generationProgress.value)

        // Reset
        bridge.reset()

        // Verify everything is cleared
        assertTrue(bridge.conversationState.value.messages.isEmpty())
        assertFalse(bridge.conversationState.value.isAgentThinking)
        assertNull(bridge.conversationState.value.currentAssetPreview)
        assertNull(bridge.generationProgress.value)
    }

    @Test
    fun `reset can be called on fresh bridge`() {
        val bridge = AgentBridgeImpl()
        // Should not throw
        bridge.reset()
        assertTrue(bridge.conversationState.value.messages.isEmpty())
    }
}
