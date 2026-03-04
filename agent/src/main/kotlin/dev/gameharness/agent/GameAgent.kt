package dev.gameharness.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import dev.gameharness.agent.bridge.AgentBridgeImpl
import dev.gameharness.agent.registry.createGameToolRegistry
import dev.gameharness.agent.strategy.gameAgentStrategy
import dev.gameharness.api.common.AssetGenerationClient
import dev.gameharness.api.elevenlabs.ElevenLabsClient
import dev.gameharness.api.gemini.GeminiClient
import dev.gameharness.api.meshy.MeshyClient
import dev.gameharness.api.suno.SunoClient
import dev.gameharness.core.config.AppConfig
import dev.gameharness.core.model.ChatRole
import dev.gameharness.core.model.Workspace
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * Orchestrates the AI agent loop: waits for user input, builds a context-aware
 * prompt (including conversation history and workspace instructions), runs a
 * KOOG [AIAgent] with the appropriate tool registry, and relays the response
 * back through the [bridge].
 *
 * API clients are only created for services whose keys are present in [config].
 * All clients are closed when [stop] is called to avoid resource leaks.
 *
 * @param config runtime configuration holding API keys and model selection
 * @param workspaceManager provides workspace I/O (asset saving, context loading)
 * @param bridge communication channel between this agent and the Compose GUI
 * @param onWorkspaceUpdated callback invoked whenever a workspace is mutated (e.g. new asset saved)
 */
class GameAgent(
    private val config: AppConfig,
    private val workspaceManager: WorkspaceManager,
    val bridge: AgentBridgeImpl,
    private val onWorkspaceUpdated: (Workspace) -> Unit = {}
) {
    private val log = LoggerFactory.getLogger(GameAgent::class.java)
    private var currentWorkspace: Workspace? = null
    private var agentJob: Job? = null
    private val activeClients = mutableListOf<AssetGenerationClient>()

    /** Sets the active workspace. Must be called before the agent processes any input. */
    fun setWorkspace(workspace: Workspace) {
        currentWorkspace = workspace
    }

    /**
     * Launches the agent loop in the given [scope].
     *
     * Initializes API clients, builds the tool registry, sends a greeting, then
     * enters a suspend loop: await user input, run the KOOG agent, send the
     * response. The loop continues until the coroutine is cancelled via [stop].
     */
    fun start(scope: CoroutineScope) {
        log.info("Starting agent with {} active capabilities", config.availableCapabilities().size)
        val executor = simpleOpenRouterExecutor(config.openRouterApiKey)

        // Only create clients for keys that are configured
        val geminiClient = config.geminiApiKey?.let { GeminiClient(it, config.nanoBananaModel) }
        val meshyClient = config.meshyApiKey?.let { MeshyClient(it) }
        val sunoClient = config.sunoApiKey?.let { SunoClient(it) }
        val elevenLabsClient = config.elevenLabsApiKey?.let { ElevenLabsClient(it) }

        activeClients.clear()
        listOfNotNull(geminiClient, meshyClient, sunoClient, elevenLabsClient)
            .forEach { activeClients.add(it) }

        val updateWorkspace: (Workspace) -> Unit = { ws ->
            currentWorkspace = ws
            onWorkspaceUpdated(ws)
        }

        val toolRegistry = createGameToolRegistry(
            geminiClient, meshyClient, sunoClient, elevenLabsClient,
            workspaceManager,
            { currentWorkspace ?: error("No workspace selected") },
            bridge,
            onProgress = { bridge.updateProgress(it) },
            onWorkspaceUpdated = updateWorkspace
        )

        val baseSystemPrompt = buildSystemPrompt(config)

        agentJob = scope.launch {
            bridge.sendAgentMessage(buildGreeting(config))

            while (isActive) {
                val userInput = bridge.awaitUserInput()
                bridge.updateThinkingState(true)

                try {
                    val fullInput = buildAgentInput(userInput)

                    // Inject workspace instructions into system prompt if available
                    val wsContext = currentWorkspace?.let {
                        try { workspaceManager.loadWorkspaceContext(it) } catch (_: Exception) { "" }
                    } ?: ""
                    val systemPrompt = if (wsContext.isNotBlank()) {
                        "$baseSystemPrompt\n\n## Workspace Instructions\nThe user has provided the following instructions for this workspace. Follow them for all asset generation:\n\n$wsContext"
                    } else {
                        baseSystemPrompt
                    }

                    val agent = AIAgent(
                        promptExecutor = executor,
                        llmModel = OpenRouterModels.Claude4_5Sonnet,
                        strategy = gameAgentStrategy(),
                        toolRegistry = toolRegistry,
                        systemPrompt = systemPrompt,
                        maxIterations = MAX_TOOL_ITERATIONS
                    )

                    val response = agent.run(fullInput)
                    bridge.sendAgentMessage(response)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Agent error processing input", e)
                    bridge.sendAgentMessage(formatErrorMessage(e))
                } finally {
                    bridge.updateThinkingState(false)
                    bridge.updateProgress(null)
                    bridge.clearAttachments()
                }
            }
        }
    }

    /**
     * Constructs the full text input sent to the KOOG agent for a single turn.
     *
     * Prepends conversation history (if any) and appends reference-image annotations
     * when the user has attached files to the current message.
     */
    internal fun buildAgentInput(latestUserMessage: String): String {
        val messages = bridge.conversationState.value.messages
        val historyMessages = messages.dropLast(1)

        val attachments = bridge.latestAttachmentPaths
        val annotatedMessage = if (attachments.isNotEmpty()) {
            val fileNames = attachments.map { java.nio.file.Path.of(it).fileName }
            val label = if (fileNames.size == 1) {
                "[Reference image attached: ${fileNames.first()}]"
            } else {
                "[Reference images attached: ${fileNames.joinToString(", ")}]"
            }
            "$latestUserMessage\n$label"
        } else {
            latestUserMessage
        }

        if (historyMessages.isEmpty()) return annotatedMessage

        val history = historyMessages.joinToString("\n") { msg ->
            when (msg.role) {
                ChatRole.USER -> "User: ${msg.content}"
                ChatRole.ASSISTANT -> "Assistant: ${msg.content}"
                ChatRole.SYSTEM -> "System: ${msg.content}"
            }
        }

        return "Previous conversation:\n$history\n\nUser: $annotatedMessage"
    }

    /** Cancels the agent loop and closes all active API clients. */
    fun stop() {
        log.info("Stopping agent, closing {} clients", activeClients.size)
        agentJob?.cancel()
        activeClients.forEach { runCatching { it.close() } }
        activeClients.clear()
    }

    companion object {
        /** Maximum number of LLM-tool round-trips allowed per user turn. */
        const val MAX_TOOL_ITERATIONS = 20

        /** Maps common exceptions to user-friendly error messages. */
        internal fun formatErrorMessage(e: Exception): String {
            val msg = e.message?.lowercase() ?: ""
            return when {
                e is java.net.UnknownHostException || e is java.net.ConnectException ->
                    "I couldn't reach the server. Please check your internet connection and try again."
                msg.contains("401") || msg.contains("unauthorized") ->
                    "Your API key appears to be invalid. Please check your keys in Settings (\u2699)."
                msg.contains("429") || msg.contains("rate limit") ->
                    "We've hit a rate limit. Please wait a moment and try again."
                msg.contains("timeout") || e is java.net.SocketTimeoutException ->
                    "The request timed out. This can happen with large assets — please try again."
                msg.contains("no workspace") ->
                    "Please select a workspace before generating assets."
                else ->
                    "Something went wrong: ${e.message ?: "Unknown error"}. Please try again."
            }
        }

        /** Builds the initial greeting message listing the agent's available capabilities. */
        internal fun buildGreeting(config: AppConfig): String {
            val capabilities = mutableListOf<String>()
            if (!config.geminiApiKey.isNullOrBlank()) capabilities.add("2D sprites")
            if (!config.meshyApiKey.isNullOrBlank()) capabilities.add("3D models")
            if (!config.sunoApiKey.isNullOrBlank()) capabilities.add("music")
            if (!config.elevenLabsApiKey.isNullOrBlank()) capabilities.add("sound effects")

            return if (capabilities.isEmpty()) {
                "Hello! I'm your Game Asset Generator. It looks like you haven't configured " +
                    "any asset generation API keys yet. Head to Settings (\u2699) to add keys " +
                    "for the services you'd like to use (Gemini for sprites, Meshy for 3D models, " +
                    "Suno for music, ElevenLabs for sound effects). I can still chat with you " +
                    "about game development in the meantime!"
            } else {
                "Hello! I'm your Game Asset Generator. I can create " +
                    capabilities.joinToString(", ") +
                    " for your game. Select a workspace and tell me what you'd like to create!"
            }
        }
    }
}
