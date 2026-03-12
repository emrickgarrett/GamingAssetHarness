package dev.gameharness.gui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import dev.gameharness.core.config.SavedSettings
import dev.gameharness.gui.components.*
import dev.gameharness.gui.theme.GameHarnessTheme
import dev.gameharness.gui.viewmodel.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest

/** Root composable that sets up the theme and renders either the first-run settings dialog or the main application screen. */
@Composable
fun App() {
    val viewModel = remember { AppViewModel() }
    val scope = rememberCoroutineScope()

    val needsSetup by viewModel.needsSetup.collectAsState()
    val savedSettings by viewModel.savedSettings.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()

    GameHarnessTheme(darkTheme = darkMode) {
        if (needsSetup) {
            SettingsDialog(
                initialSettings = savedSettings,
                isFirstRun = true,
                onSave = { viewModel.saveSettings(it) },
                onDismiss = {}
            )
        } else {
            MainScreen(viewModel, scope, savedSettings)
        }
    }
}

/**
 * Primary application layout with a top bar (workspace selector, capability chips, settings),
 * a chat panel on the left, and the asset preview / action bar on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: AppViewModel,
    scope: CoroutineScope,
    savedSettings: SavedSettings
) {
    val conversationState by viewModel.conversationState.collectAsState()
    val generationProgress by viewModel.generationProgress.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val currentWorkspace by viewModel.currentWorkspace.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val currentFolder by viewModel.currentFolder.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showNewWorkspace by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collectLatest { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.startAgent(scope)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopAgent() }
    }

    Box(
        modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                when {
                    event.key == Key.Comma -> {
                        showSettings = true
                        true
                    }
                    event.isShiftPressed && event.key == Key.N -> {
                        showNewWorkspace = true
                        true
                    }
                    else -> false
                }
            } else false
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WorkspaceSelector(
                            workspaces = workspaces,
                            currentWorkspace = currentWorkspace,
                            onWorkspaceSelected = { viewModel.selectWorkspace(it) },
                            onWorkspaceCreated = { name, path -> viewModel.createWorkspace(name, path) },
                            onWorkspaceRenamed = { ws, name -> viewModel.renameWorkspace(ws, name) },
                            onWorkspaceDeleted = { viewModel.deleteWorkspace(it) },
                            modifier = Modifier.weight(1f)
                        )

                        // Workspace instructions icon
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Workspace Instructions") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(
                                onClick = { showContextDialog = true },
                                enabled = currentWorkspace != null
                            ) {
                                Text(
                                    text = "\uD83D\uDCDD",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (currentWorkspace != null)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }

                        CapabilityChips(capabilities)

                        Spacer(Modifier.width(4.dp))

                        // Settings gear icon
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Settings (Ctrl+,)") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(onClick = { showSettings = true }) {
                                Text(
                                    text = "\u2699",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        ) { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                ChatPanel(
                    messages = conversationState.messages,
                    isAgentThinking = conversationState.isAgentThinking,
                    onSendMessage = { text, attachments -> viewModel.sendMessage(text, attachments) },
                    onNewChat = { viewModel.resetConversation() },
                    modifier = Modifier.weight(0.45f)
                )

                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                Column(
                    modifier = Modifier.weight(0.55f).fillMaxHeight()
                ) {
                    AssetPreview(
                        preview = conversationState.currentAssetPreview,
                        assets = currentWorkspace?.assets ?: emptyList(),
                        capabilities = capabilities,
                        folders = currentWorkspace?.folders ?: emptySet(),
                        currentFolder = currentFolder,
                        onDeleteAsset = { viewModel.deleteAsset(it) },
                        onDeleteAssets = { viewModel.deleteAssets(it) },
                        onReviseAsset = { asset, request -> viewModel.requestRevision(asset, request) },
                        onMoveAsset = { assetId, folder -> viewModel.moveAssetToFolder(assetId, folder) },
                        onMoveAssets = { ids, folder -> viewModel.moveAssetsToFolder(ids, folder) },
                        onNavigateToFolder = { viewModel.navigateToFolder(it) },
                        onNavigateUp = { viewModel.navigateUp() },
                        onCreateFolder = { viewModel.createFolder(it) },
                        onDeleteFolder = { viewModel.deleteFolder(it) },
                        onRenameFolder = { path, newName -> viewModel.renameFolder(path, newName) },
                        onSplitSheet = { asset, w, h, skip, bgColor, bgTol, folder ->
                            viewModel.splitSpriteSheet(asset, w, h, skip, bgColor, bgTol, folder)
                        },
                        modifier = Modifier.weight(1f).padding(8.dp)
                    )

                    AssetActionBar(
                        isVisible = conversationState.currentAssetPreview != null,
                        onDecision = { viewModel.submitDecision(it) }
                    )

                    GenerationProgressBar(
                        progress = generationProgress,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initialSettings = savedSettings,
            isFirstRun = false,
            onSave = {
                viewModel.saveSettings(it)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showNewWorkspace) {
        NewWorkspaceDialog(
            onConfirm = { name, path ->
                viewModel.createWorkspace(name, path)
                showNewWorkspace = false
            },
            onDismiss = { showNewWorkspace = false }
        )
    }

    if (showContextDialog && currentWorkspace != null) {
        WorkspaceContextDialog(
            initialContext = viewModel.loadWorkspaceContext(),
            onSave = { context ->
                viewModel.saveWorkspaceContext(context)
                showContextDialog = false
            },
            onDismiss = { showContextDialog = false }
        )
    }
}

@Composable
private fun CapabilityChips(capabilities: List<String>) {
    val assetCapabilities = capabilities.drop(1)
    if (assetCapabilities.isEmpty()) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        assetCapabilities.forEach { cap ->
            val shortLabel = when {
                cap.contains("Sprites") -> "2D"
                cap.contains("3D") -> "3D"
                cap.contains("Music") -> "Music"
                cap.contains("Sound") -> "SFX"
                else -> cap.take(4)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = shortLabel,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

