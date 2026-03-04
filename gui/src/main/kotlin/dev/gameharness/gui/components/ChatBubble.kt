package dev.gameharness.gui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.ChatMessage
import dev.gameharness.core.model.ChatRole
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

/** Renders a single chat message as a styled bubble, aligned right for user messages and left for agent messages. */
@Composable
fun ChatBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = message.role == ChatRole.USER
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier.widthIn(max = 500.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
            ) {
                Text(
                    text = if (isUser) "You" else "Agent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(2.dp))

            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 12.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 12.dp,
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp
                ),
                color = bubbleColor
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Show attachment thumbnails if present
                    val paths = message.attachmentPaths
                    if (paths.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (attachPath in paths) {
                                val thumbnail = remember(attachPath) {
                                    loadThumbnail(attachPath)
                                }
                                if (thumbnail != null) {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = "Attached reference image",
                                        modifier = Modifier
                                            .heightIn(max = 80.dp)
                                            .widthIn(max = 120.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        text = "\uD83D\uDCCE ${File(attachPath).name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }

                    Text(
                        text = message.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun loadThumbnail(path: String): ImageBitmap? {
    return try {
        val file = File(path)
        if (file.exists()) {
            ImageIO.read(file)?.toComposeImageBitmap()
        } else null
    } catch (_: Exception) { null }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm")
    return sdf.format(Date(timestamp))
}
