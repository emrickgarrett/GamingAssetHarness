package dev.gameharness.gui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.AssetType
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.*

/** Renders a sprite image from the filesystem. Shows an error message if the file cannot be loaded. */
@Composable
internal fun ImagePreview(filePath: String, modifier: Modifier = Modifier) {
    val imageBitmap = remember(filePath) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                val bufferedImage = ImageIO.read(file)
                bufferedImage?.toComposeImageBitmap()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    Box(
        modifier = modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Generated sprite",
                modifier = Modifier.padding(8.dp).fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = "Unable to load image",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Renders an audio player with play/stop controls for music or sound effect files. */
@Composable
internal fun AudioPreview(filePath: String, assetType: AssetType, modifier: Modifier = Modifier) {
    val file = File(filePath)
    var isPlaying by remember { mutableStateOf(false) }
    var clip by remember { mutableStateOf<Clip?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(filePath) {
        onDispose {
            clip?.stop()
            clip?.close()
            clip = null
        }
    }

    Box(
        modifier = modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = if (assetType == AssetType.MUSIC) "\uD83C\uDFB5" else "\uD83D\uDD0A",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (file.exists()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${file.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(16.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    if (isPlaying) {
                        clip?.stop()
                        isPlaying = false
                    } else {
                        try {
                            clip?.stop()
                            clip?.close()

                            val audioStream = AudioSystem.getAudioInputStream(file)
                            val baseFormat = audioStream.format
                            val decodedFormat = AudioFormat(
                                AudioFormat.Encoding.PCM_SIGNED,
                                baseFormat.sampleRate,
                                16,
                                baseFormat.channels,
                                baseFormat.channels * 2,
                                baseFormat.sampleRate,
                                false
                            )
                            val decodedStream = AudioSystem.getAudioInputStream(decodedFormat, audioStream)

                            val newClip = AudioSystem.getClip()
                            newClip.open(decodedStream)
                            newClip.addLineListener { event ->
                                if (event.type == LineEvent.Type.STOP) {
                                    isPlaying = false
                                }
                            }
                            newClip.start()
                            clip = newClip
                            isPlaying = true
                            errorMessage = null
                        } catch (e: Exception) {
                            errorMessage = "Cannot play: ${e.message?.take(60)}"
                            isPlaying = false
                        }
                    }
                },
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(if (isPlaying) "\u23F9  Stop" else "\u25B6  Play")
            }
        }
    }
}

/** Renders a 3D model info card with file name, size, and format badge. */
@Composable
internal fun ModelPreview(filePath: String, modifier: Modifier = Modifier) {
    val file = File(filePath)

    Box(
        modifier = modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "\uD83E\uDDF1",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (file.exists()) {
                Spacer(Modifier.height(4.dp))
                val sizeKb = file.length() / 1024
                val sizeDisplay = if (sizeKb > 1024) "${"%.1f".format(sizeKb / 1024.0)} MB" else "$sizeKb KB"
                Text(
                    text = sizeDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "GLB",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Open in a 3D viewer to inspect the model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

/** Renders a generic file info card for unsupported asset types. */
@Composable
internal fun FileInfoCard(filePath: String, modifier: Modifier = Modifier) {
    val file = File(filePath)

    Box(
        modifier = modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (file.exists()) {
                Text(
                    text = "${file.length() / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = file.extension.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/** Renders a large emoji icon for asset type placeholders. */
@Composable
internal fun AssetIcon(icon: String) {
    Text(
        text = icon,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center
    )
}

/** Renders a small sprite thumbnail from the filesystem. Used in asset cards. */
@Composable
internal fun SpriteThumbnail(filePath: String) {
    val imageBitmap = remember(filePath) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                ImageIO.read(file)?.toComposeImageBitmap()
            } else null
        } catch (_: Exception) { null }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "Sprite thumbnail",
            modifier = Modifier.fillMaxSize().padding(6.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        AssetIcon("\uD83D\uDDBC")
    }
}
