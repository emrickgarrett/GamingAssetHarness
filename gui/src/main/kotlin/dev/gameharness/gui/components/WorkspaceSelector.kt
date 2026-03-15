package dev.gameharness.gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.Workspace
import dev.gameharness.gui.LocalAwtWindow
import dev.gameharness.gui.util.pickFolder
import java.nio.file.Files
import java.nio.file.Path

/** Dropdown selector for switching between workspaces, with inline rename/delete actions and a "New" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSelector(
    workspaces: List<Workspace>,
    currentWorkspace: Workspace?,
    onWorkspaceSelected: (Workspace) -> Unit,
    onWorkspaceCreated: (String, Path) -> Unit,
    onWorkspaceOpened: (String, Path) -> Unit = { _, _ -> },
    onWorkspaceRenamed: (Workspace, String) -> Unit = { _, _ -> },
    onWorkspaceDeleted: (Workspace) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Workspace?>(null) }
    var deleteTarget by remember { mutableStateOf<Workspace?>(null) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Workspace:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.width(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = currentWorkspace?.name ?: "Select workspace...",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                workspaces.forEach { workspace ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(workspace.name)
                                    Text(
                                        text = workspace.directoryPath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row {
                                    TextButton(
                                        onClick = {
                                            renameTarget = workspace
                                            expanded = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            "Rename",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            deleteTarget = workspace
                                            expanded = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            "Delete",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            onWorkspaceSelected(workspace)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        OutlinedButton(onClick = { showOpenDialog = true }) {
            Text("Open")
        }

        Spacer(Modifier.width(4.dp))

        Button(
            onClick = { showNewDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("+ New")
        }
    }

    if (showNewDialog) {
        NewWorkspaceDialog(
            onConfirm = { name, path ->
                onWorkspaceCreated(name, path)
                showNewDialog = false
            },
            onDismiss = { showNewDialog = false }
        )
    }

    if (showOpenDialog) {
        OpenWorkspaceDialog(
            onConfirm = { name, path ->
                onWorkspaceOpened(name, path)
                showOpenDialog = false
            },
            onDismiss = { showOpenDialog = false }
        )
    }

    renameTarget?.let { workspace ->
        WorkspaceNameDialog(
            title = "Rename Workspace",
            label = "New name",
            placeholder = workspace.name,
            initialValue = workspace.name,
            confirmText = "Rename",
            onConfirm = { newName ->
                onWorkspaceRenamed(workspace, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { workspace ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Workspace") },
            text = {
                Text(
                    "Are you sure you want to delete '${workspace.name}'? " +
                        "All assets and chat history in this workspace will be permanently removed."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onWorkspaceDeleted(workspace)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Dialog for creating a new workspace with a name and folder picker.
 * Public so it can be reused from App.kt for the keyboard shortcut dialog.
 */
@Composable
fun NewWorkspaceDialog(
    onConfirm: (String, Path) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedPath by remember { mutableStateOf<Path?>(null) }
    val awtWindow = LocalAwtWindow.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    placeholder = { Text("My RPG Project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedPath?.toString() ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Folder") },
                        placeholder = { Text("Choose a folder...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val picked = pickFolder(awtWindow)
                        if (picked != null) {
                            selectedPath = picked
                        }
                    }) {
                        Text("Browse...")
                    }
                }

                if (selectedPath != null) {
                    Text(
                        text = "Assets will be saved in: $selectedPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val path = selectedPath
                    if (name.isNotBlank() && path != null) {
                        onConfirm(name.trim(), path)
                    }
                },
                enabled = name.isNotBlank() && selectedPath != null
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for opening an existing directory as a workspace.
 * Shows a folder picker and a name field. If the selected folder
 * already contains workspace.json, the name auto-populates from it.
 * Public so it can be reused from App.kt for the keyboard shortcut dialog.
 */
@Composable
fun OpenWorkspaceDialog(
    onConfirm: (String, Path) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedPath by remember { mutableStateOf<Path?>(null) }
    var hasExistingMetadata by remember { mutableStateOf(false) }
    val awtWindow = LocalAwtWindow.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Folder as Workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedPath?.toString() ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Folder") },
                        placeholder = { Text("Choose a folder...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val picked = pickFolder(awtWindow, "Open Folder as Workspace")
                        if (picked != null) {
                            selectedPath = picked
                            val metadataFile = picked.resolve("workspace.json")
                            if (metadataFile.toFile().exists()) {
                                hasExistingMetadata = true
                                try {
                                    val content = Files.readString(metadataFile)
                                    // Extract name from JSON without requiring serialization dependency
                                    val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(content)
                                    name = nameMatch?.groupValues?.get(1) ?: picked.fileName?.toString() ?: ""
                                } catch (_: Exception) {
                                    name = picked.fileName?.toString() ?: ""
                                }
                            } else {
                                hasExistingMetadata = false
                                name = picked.fileName?.toString() ?: ""
                            }
                        }
                    }) {
                        Text("Browse...")
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!hasExistingMetadata) name = it },
                    label = { Text("Workspace name") },
                    placeholder = { Text("My Game Project") },
                    singleLine = true,
                    readOnly = hasExistingMetadata,
                    modifier = Modifier.fillMaxWidth()
                )

                if (hasExistingMetadata) {
                    Text(
                        text = "This folder contains existing workspace metadata. " +
                            "It will be imported with its existing name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (selectedPath != null) {
                    Text(
                        text = "A workspace will be initialized in this folder. " +
                            "Existing files will not be modified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val path = selectedPath
                    if (name.isNotBlank() && path != null) {
                        onConfirm(name.trim(), path)
                    }
                },
                enabled = name.isNotBlank() && selectedPath != null
            ) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun WorkspaceNameDialog(
    title: String,
    label: String,
    placeholder: String,
    initialValue: String = "",
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for editing per-workspace instructions/context that persists across sessions.
 * These instructions are injected into the agent's system prompt.
 */
@Composable
fun WorkspaceContextDialog(
    initialContext: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var context by remember { mutableStateOf(initialContext) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspace Instructions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "These instructions are included with every message sent to the AI agent. " +
                        "Use them for styling preferences, format requirements, or project-specific directions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it },
                    label = { Text("Instructions") },
                    placeholder = { Text("e.g. All sprites should use a 16-bit pixel art style with a 64x64 resolution. Use a dark fantasy color palette...") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(context) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
