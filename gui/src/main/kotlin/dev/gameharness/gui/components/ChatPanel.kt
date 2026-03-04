package dev.gameharness.gui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.ChatMessage
import dev.gameharness.gui.LocalAwtWindow
import dev.gameharness.gui.util.pickImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Scrollable chat panel displaying conversation messages, a thinking indicator, image attachment
 * management, and a text input field with send button. Supports Enter-to-send and image attachments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    isAgentThinking: Boolean,
    onSendMessage: (text: String, attachmentPaths: List<String>) -> Unit,
    onNewChat: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var attachmentPaths by remember { mutableStateOf(listOf<String>()) }
    val listState = rememberLazyListState()
    val awtWindow = LocalAwtWindow.current

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxHeight()) {
        // Chat header with New Chat button
        if (onNewChat != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Chat",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Start a new conversation") } },
                        state = rememberTooltipState()
                    ) {
                        TextButton(
                            onClick = onNewChat,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "\uD83D\uDCAC New Chat",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }

        // Messages list (wrapped in SelectionContainer for copy-paste support)
        SelectionContainer(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.timestamp }) { message ->
                    ChatBubble(message = message)
                }

                // Thinking indicator
                if (isAgentThinking) {
                    item {
                        ThinkingIndicator()
                    }
                }
            }
        }

        // Attachment preview strip (horizontal scrollable row of thumbnails)
        if (attachmentPaths.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 1.dp
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(attachmentPaths) { path ->
                        AttachmentChip(
                            path = path,
                            onRemove = { attachmentPaths = attachmentPaths - path }
                        )
                    }
                }
            }
        }

        // Input area
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach image button
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Attach reference image(s)") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(
                        onClick = {
                            val path = pickImage(awtWindow)
                            if (path != null) {
                                val absolutePath = path.toAbsolutePath().toString()
                                if (absolutePath !in attachmentPaths) {
                                    attachmentPaths = attachmentPaths + absolutePath
                                }
                            }
                        },
                        enabled = !isAgentThinking
                    ) {
                        Text(
                            text = "\uD83D\uDCCE",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (!isAgentThinking)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).onKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                            if (inputText.isNotBlank() && !isAgentThinking) {
                                onSendMessage(inputText.trim(), attachmentPaths)
                                inputText = ""
                                attachmentPaths = emptyList()
                            }
                            true
                        } else false
                    },
                    placeholder = {
                        Text(
                            if (isAgentThinking) "Agent is thinking..."
                            else "Describe the asset you need..."
                        )
                    },
                    enabled = !isAgentThinking,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText.trim(), attachmentPaths)
                            inputText = ""
                            attachmentPaths = emptyList()
                        }
                    },
                    enabled = inputText.isNotBlank() && !isAgentThinking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(path: String, onRemove: () -> Unit) {
    val thumbnail: ImageBitmap? = remember(path) {
        try {
            val file = File(path)
            if (file.exists()) ImageIO.read(file)?.toComposeImageBitmap() else null
        } catch (_: Exception) { null }
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Attached image preview",
                    modifier = Modifier
                        .height(40.dp)
                        .widthIn(max = 60.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = File(path).name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp)
            ) {
                Text(
                    text = "\u2715",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Agent is thinking...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}
