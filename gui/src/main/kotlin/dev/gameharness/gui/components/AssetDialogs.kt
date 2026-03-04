package dev.gameharness.gui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Confirmation dialog for deleting a single asset by name. */
@Composable
internal fun DeleteConfirmDialog(
    assetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Asset") },
        text = {
            Text("Are you sure you want to delete \"$assetName\"? This will remove the file from disk and cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Dialog for submitting a revision request for an existing asset. */
@Composable
internal fun ReviseAssetDialog(
    assetName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var revisionRequest by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Revise Asset") },
        text = {
            Column {
                Text(
                    text = "Describe the changes you'd like for \"$assetName\":",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = revisionRequest,
                    onValueChange = { revisionRequest = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., Make it darker, add a shadow, change the color to blue...") },
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(revisionRequest) },
                enabled = revisionRequest.isNotBlank()
            ) {
                Text("Send to Agent")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Dialog for creating a new folder in the asset browser. */
@Composable
internal fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it.replace("/", "") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Folder name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(folderName.trim()) },
                enabled = folderName.isNotBlank()
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

/** Dialog for renaming an existing folder. */
@Composable
internal fun RenameFolderDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Folder") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.replace("/", "") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("New name") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newName.trim()) },
                enabled = newName.isNotBlank() && newName.trim() != currentName
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Confirmation dialog for deleting a folder, showing how many assets are affected. */
@Composable
internal fun DeleteFolderConfirmDialog(
    folderName: String,
    assetCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Folder") },
        text = {
            val assetNote = if (assetCount > 0) {
                " $assetCount asset${if (assetCount != 1) "s" else ""} will be moved to the parent folder."
            } else ""
            Text("Are you sure you want to delete \"$folderName\"?$assetNote")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Dialog for selecting a destination folder when moving assets. */
@Composable
internal fun MoveToFolderDialog(
    folders: Set<String>,
    currentFolder: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFolder by remember { mutableStateOf(currentFolder) }

    // Build sorted list: Root first, then all folders sorted alphabetically
    val folderList = listOf("" to "Root (top level)") +
        folders.sorted().map { path ->
            val depth = path.count { it == '/' }
            val indent = "\u00A0\u00A0\u00A0\u00A0".repeat(depth)
            path to "$indent${path.substringAfterLast("/")}"
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Folder") },
        text = {
            Column {
                Text(
                    text = "Select destination folder:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    folderList.forEach { (path, displayName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolder = path }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedFolder == path,
                                onClick = { selectedFolder = path }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedFolder) },
                enabled = selectedFolder != currentFolder
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Confirmation dialog for batch-deleting multiple selected assets. */
@Composable
internal fun BatchDeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count Assets") },
        text = {
            Text("Are you sure you want to delete $count asset${if (count != 1) "s" else ""}? This will remove the files from disk and cannot be undone.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
