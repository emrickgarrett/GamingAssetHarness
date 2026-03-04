package dev.gameharness.gui.viewmodel

import dev.gameharness.agent.GameAgent
import dev.gameharness.agent.bridge.AgentBridgeImpl
import dev.gameharness.agent.bridge.ConversationState
import dev.gameharness.agent.bridge.GenerationProgress
import dev.gameharness.core.config.AppConfig
import dev.gameharness.core.config.SavedSettings
import dev.gameharness.core.config.SettingsManager
import dev.gameharness.core.model.AssetReviewDecision
import dev.gameharness.core.model.AssetType
import dev.gameharness.core.model.GeneratedAsset
import dev.gameharness.core.model.Workspace
import dev.gameharness.core.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * MVVM state holder for the application. Manages agent lifecycle, workspace CRUD,
 * settings persistence, folder navigation, and bridges UI events to the [GameAgent].
 */
class AppViewModel(
    private val settingsManager: SettingsManager = SettingsManager(),
    private val workspaceManager: WorkspaceManager = WorkspaceManager(
        Paths.get(System.getProperty("user.home"), ".gameharness")
    )
) {
    private val log = LoggerFactory.getLogger(AppViewModel::class.java)

    private var config: AppConfig? = null

    /** Bridge for bidirectional communication between the UI and the AI agent. */
    val bridge = AgentBridgeImpl()
    private var gameAgent: GameAgent? = null
    private var agentScope: CoroutineScope? = null

    private val _needsSetup = MutableStateFlow(true)
    /** Whether the app requires initial setup (true when no OpenRouter key is configured). */
    val needsSetup: StateFlow<Boolean> = _needsSetup.asStateFlow()

    private val _savedSettings = MutableStateFlow(SavedSettings())
    /** Current persisted settings including API keys and UI preferences. */
    val savedSettings: StateFlow<SavedSettings> = _savedSettings.asStateFlow()

    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    /** All known workspaces from the workspace registry. */
    val workspaces: StateFlow<List<Workspace>> = _workspaces.asStateFlow()

    private val _currentWorkspace = MutableStateFlow<Workspace?>(null)
    /** The currently selected workspace, or null if none is selected. */
    val currentWorkspace: StateFlow<Workspace?> = _currentWorkspace.asStateFlow()

    private val _capabilities = MutableStateFlow<List<String>>(emptyList())
    /** List of available asset generation capabilities based on configured API keys. */
    val capabilities: StateFlow<List<String>> = _capabilities.asStateFlow()

    private val _currentFolder = MutableStateFlow("")
    /** Path of the currently navigated folder within the workspace asset hierarchy. */
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    private val _darkMode = MutableStateFlow(true)
    /** Whether dark theme is enabled. */
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 5)
    /** One-shot messages to display in the UI snackbar. */
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    /** Current conversation state including messages, thinking status, and asset preview. */
    val conversationState: StateFlow<ConversationState> = bridge.conversationState
    /** Progress updates for long-running asset generation tasks (Suno music, Meshy 3D models). */
    val generationProgress: StateFlow<GenerationProgress?> = bridge.generationProgress

    init {
        loadConfig()
        migrateLegacyWorkspaces()
        refreshWorkspaces()
    }

    private fun loadConfig() {
        val loaded = AppConfig.fromSettings(settingsManager)
        if (loaded != null) {
            config = loaded
            _capabilities.value = loaded.availableCapabilities()
            _needsSetup.value = false
            log.info("Config loaded from settings, capabilities: {}", loaded.availableCapabilities())
        } else {
            try {
                val envConfig = AppConfig.fromEnvironment()
                config = envConfig
                _capabilities.value = envConfig.availableCapabilities()
                _needsSetup.value = false
                log.info("Config loaded from environment")
            } catch (e: IllegalStateException) {
                _needsSetup.value = true
                log.info("No config found, showing setup screen")
            }
        }

        val saved = settingsManager.load() ?: SavedSettings()
        _savedSettings.value = saved
        _darkMode.value = saved.darkMode
    }

    /**
     * One-time migration: import any workspaces from the legacy ./workspaces/ directory
     * into the new registry-based system.
     */
    private fun migrateLegacyWorkspaces() {
        val legacyRoot = Paths.get(".", "workspaces")
        if (!legacyRoot.exists()) return

        try {
            Files.list(legacyRoot).use { stream ->
                stream.filter { it.isDirectory() }
                    .filter { it.resolve("workspace.json").exists() }
                    .forEach { dir ->
                        workspaceManager.importWorkspace(dir)
                    }
            }
        } catch (e: Exception) {
            log.info("No legacy workspaces to migrate: {}", e.message)
        }
    }

    /** Persists the given settings, restarts the agent with the new configuration, and emits a snackbar confirmation. */
    fun saveSettings(settings: SavedSettings) {
        settingsManager.save(settings)
        _savedSettings.value = settings
        _darkMode.value = settings.darkMode

        stopAgent()
        loadConfig()

        val scope = agentScope
        if (scope != null && config != null) {
            startAgent(scope)
        }

        _snackbarMessage.tryEmit("Settings saved")
    }

    /** Toggles dark/light theme and persists the preference. */
    fun toggleDarkMode() {
        val newMode = !_darkMode.value
        _darkMode.value = newMode
        val current = _savedSettings.value.copy(darkMode = newMode)
        _savedSettings.value = current
        settingsManager.save(current)
    }

    /** Sends a user message (with optional image attachment paths) to the AI agent via the bridge. */
    fun sendMessage(text: String, attachmentPaths: List<String> = emptyList()) {
        if (text.isBlank()) return
        bridge.submitUserInput(text, attachmentPaths)
    }

    /** Submits the user's approve/deny decision for a presented asset. */
    fun submitDecision(decision: AssetReviewDecision) {
        bridge.submitAssetDecision(decision)
    }

    /** Switches to the given workspace, saving the current chat history and loading the new one. */
    fun selectWorkspace(workspace: Workspace) {
        saveChatHistory()
        _currentWorkspace.value = workspace
        _currentFolder.value = ""
        gameAgent?.setWorkspace(workspace)
        loadChatHistory(workspace)
    }

    /** Creates a new workspace at the specified directory and selects it. */
    fun createWorkspace(name: String, directory: Path) {
        try {
            val workspace = workspaceManager.createWorkspace(name, directory)
            refreshWorkspaces()
            selectWorkspace(workspace)
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to create workspace: ${e.message}")
        }
    }

    /** Renames the given workspace and updates the agent if it is the current workspace. */
    fun renameWorkspace(workspace: Workspace, newName: String) {
        try {
            val renamed = workspaceManager.renameWorkspace(workspace, newName)
            refreshWorkspaces()
            if (_currentWorkspace.value?.directoryPath == workspace.directoryPath) {
                _currentWorkspace.value = renamed
                gameAgent?.setWorkspace(renamed)
            }
            _snackbarMessage.tryEmit("Workspace renamed to '$newName'")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to rename workspace: ${e.message}")
        }
    }

    /** Deletes the workspace and its contents; clears the selection if it was the current workspace. */
    fun deleteWorkspace(workspace: Workspace) {
        try {
            workspaceManager.deleteWorkspace(workspace)
            refreshWorkspaces()
            if (_currentWorkspace.value?.directoryPath == workspace.directoryPath) {
                _currentWorkspace.value = null
            }
            _snackbarMessage.tryEmit("Workspace '${workspace.name}' deleted")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to delete workspace: ${e.message}")
        }
    }

    /** Deletes a single asset by ID from the current workspace. */
    fun deleteAsset(assetId: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            _currentWorkspace.value = workspaceManager.deleteAsset(ws, assetId)
            _snackbarMessage.tryEmit("Asset deleted")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to delete asset: ${e.message}")
        }
    }

    /** Deletes multiple assets by their IDs from the current workspace. */
    fun deleteAssets(assetIds: Set<String>) {
        val ws = _currentWorkspace.value ?: return
        try {
            _currentWorkspace.value = workspaceManager.deleteAssets(ws, assetIds)
            _snackbarMessage.tryEmit("${assetIds.size} asset${if (assetIds.size != 1) "s" else ""} deleted")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to delete assets: ${e.message}")
        }
    }

    /** Moves multiple assets into the specified folder within the current workspace. */
    fun moveAssetsToFolder(assetIds: Set<String>, targetFolder: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            _currentWorkspace.value = workspaceManager.moveAssetsToFolder(ws, assetIds, targetFolder)
            _snackbarMessage.tryEmit("${assetIds.size} asset${if (assetIds.size != 1) "s" else ""} moved")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to move assets: ${e.message}")
        }
    }

    /**
     * Sends a revision request for an existing asset back to the agent as a chat message.
     *
     * For sprites, the original image file is automatically attached as a reference so
     * the Gemini API can see what it's revising rather than generating from scratch.
     */
    fun requestRevision(asset: GeneratedAsset, userRequest: String) {
        val message = "Please revise the ${asset.type.displayName} \"${asset.fileName}\": $userRequest"
        val attachments = if (asset.type == AssetType.SPRITE) listOf(asset.filePath) else emptyList()
        sendMessage(message, attachmentPaths = attachments)
    }

    // --- Folder management ---

    /** Navigates the asset browser into the given folder path. */
    fun navigateToFolder(folderPath: String) {
        _currentFolder.value = folderPath
    }

    /** Navigates up one level in the folder hierarchy, or to the root if already at depth 1. */
    fun navigateUp() {
        val current = _currentFolder.value
        _currentFolder.value = current.substringBeforeLast("/", "")
    }

    /** Creates a new subfolder under the current folder in the active workspace. */
    fun createFolder(name: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            val currentPath = _currentFolder.value
            val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
            _currentWorkspace.value = workspaceManager.createFolder(ws, fullPath)
            _snackbarMessage.tryEmit("Folder '$name' created")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to create folder: ${e.message}")
        }
    }

    /** Deletes a folder and adjusts the current navigation path if it was inside the deleted folder. */
    fun deleteFolder(folderPath: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            _currentWorkspace.value = workspaceManager.deleteFolder(ws, folderPath)
            if (_currentFolder.value == folderPath || _currentFolder.value.startsWith("$folderPath/")) {
                _currentFolder.value = folderPath.substringBeforeLast("/", "")
            }
            _snackbarMessage.tryEmit("Folder deleted")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to delete folder: ${e.message}")
        }
    }

    /** Renames a folder and updates the current navigation path if affected. */
    fun renameFolder(folderPath: String, newName: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            _currentWorkspace.value = workspaceManager.renameFolder(ws, folderPath, newName)
            val parentPath = folderPath.substringBeforeLast("/", "")
            val newPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
            if (_currentFolder.value == folderPath) {
                _currentFolder.value = newPath
            } else if (_currentFolder.value.startsWith("$folderPath/")) {
                _currentFolder.value = _currentFolder.value.replaceFirst(folderPath, newPath)
            }
            _snackbarMessage.tryEmit("Folder renamed to '$newName'")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to rename folder: ${e.message}")
        }
    }

    /** Moves a single asset into the specified folder within the current workspace. */
    fun moveAssetToFolder(assetId: String, targetFolder: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            _currentWorkspace.value = workspaceManager.moveAssetToFolder(ws, assetId, targetFolder)
            _snackbarMessage.tryEmit("Asset moved")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to move asset: ${e.message}")
        }
    }

    /** Loads the workspace-specific AI instructions from the workspace-instructions.md file. */
    fun loadWorkspaceContext(): String {
        val ws = _currentWorkspace.value ?: return ""
        return try {
            workspaceManager.loadWorkspaceContext(ws)
        } catch (_: Exception) { "" }
    }

    /** Saves per-workspace AI instructions to the workspace-instructions.md file. */
    fun saveWorkspaceContext(context: String) {
        val ws = _currentWorkspace.value ?: return
        try {
            workspaceManager.saveWorkspaceContext(ws, context)
            _snackbarMessage.tryEmit("Workspace instructions saved")
        } catch (e: Exception) {
            _snackbarMessage.tryEmit("Failed to save instructions: ${e.message}")
        }
    }

    /** Clears the current conversation, saves chat history, and restarts the agent. */
    fun resetConversation() {
        saveChatHistory()
        stopAgent()
        bridge.reset()

        val scope = agentScope
        if (scope != null && config != null) {
            startAgent(scope)
        }
    }

    /** Initializes and starts the [GameAgent] in the given coroutine scope. No-op if already running or unconfigured. */
    fun startAgent(scope: CoroutineScope) {
        val cfg = config ?: return

        agentScope = scope
        if (gameAgent != null) return

        val agent = GameAgent(cfg, workspaceManager, bridge, onWorkspaceUpdated = { ws ->
            if (_currentWorkspace.value?.directoryPath == ws.directoryPath) {
                _currentWorkspace.value = ws
            }
        })
        gameAgent = agent

        val ws = _currentWorkspace.value
        if (ws != null) {
            agent.setWorkspace(ws)
        }

        agent.start(scope)
    }

    /** Stops the running agent, saves chat history, and releases API client resources. */
    fun stopAgent() {
        saveChatHistory()
        gameAgent?.stop()
        gameAgent = null
    }

    private fun saveChatHistory() {
        val ws = _currentWorkspace.value ?: return
        val messages = bridge.conversationState.value.messages
        if (messages.isNotEmpty()) {
            try {
                workspaceManager.saveChatHistory(ws, messages)
            } catch (e: Exception) {
                log.warn("Failed to save chat history: {}", e.message)
            }
        }
    }

    private fun loadChatHistory(workspace: Workspace) {
        try {
            val messages = workspaceManager.loadChatHistory(workspace)
            if (messages.isNotEmpty()) {
                bridge.restoreMessages(messages)
            }
        } catch (e: Exception) {
            log.warn("Failed to load chat history: {}", e.message)
        }
    }

    private fun refreshWorkspaces() {
        _workspaces.value = workspaceManager.listWorkspaces()
    }
}
