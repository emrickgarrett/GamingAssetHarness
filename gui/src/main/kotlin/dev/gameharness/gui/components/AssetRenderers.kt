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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.AssetType
import dev.gameharness.gui.thumbs.LocalThumbnailStore
import dev.gameharness.gui.thumbs.ThumbnailKind
import dev.gameharness.gui.thumbs.ThumbnailState
import dev.gameharness.gui.thumbs.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import javax.sound.sampled.*

/**
 * Renders a sprite at full size. The file is decoded off the UI thread; until it
 * arrives the grid's thumbnail of the same file stands in, so opening a large
 * image never stalls the window.
 */
@Composable
internal fun ImagePreview(filePath: String, modifier: Modifier = Modifier) {
    val store = LocalThumbnailStore.current
    val placeholder = remember(filePath) { store.cached(filePath) }
    val full by produceState<FullImage>(FullImage.Loading, filePath) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) FullImage.Failed
                else Image.makeFromEncoded(file.readBytes()).use { FullImage.Ready(it.toComposeImageBitmap()) }
            } catch (e: Exception) {
                FullImage.Failed
            }
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
        when (val image = full) {
            is FullImage.Ready -> Image(
                bitmap = image.bitmap,
                contentDescription = "Generated sprite",
                modifier = Modifier.padding(8.dp).fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            FullImage.Loading -> if (placeholder != null) {
                Image(
                    bitmap = placeholder,
                    contentDescription = "Generated sprite",
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator()
            }
            FullImage.Failed -> Text(
                text = "Unable to load image",
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private sealed interface FullImage {
    data object Loading : FullImage
    data object Failed : FullImage
    class Ready(val bitmap: ImageBitmap) : FullImage
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

/** Renders a 3D model with an interactive OpenGL viewer. Falls back to info card on error. */
@Composable
internal fun ModelPreview(filePath: String, modifier: Modifier = Modifier) {
    dev.gameharness.gui.viewer3d.ModelViewer(filePath, modifier)
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
internal fun AssetIcon(icon: String, alpha: Float = 1f) {
    Text(
        text = icon,
        style = MaterialTheme.typography.headlineLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.alpha(alpha)
    )
}

/**
 * Renders the small picture of an asset file on its card: a sprite scaled down,
 * or a 3D model drawn from the front. [fallbackIcon] shows while there is no
 * picture, and stays if one cannot be made.
 */
@Composable
internal fun AssetThumbnail(filePath: String, kind: ThumbnailKind, fallbackIcon: String) {
    when (val thumbnail = rememberThumbnail(filePath, kind)) {
        is ThumbnailState.Ready -> Image(
            bitmap = thumbnail.bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(6.dp),
            contentScale = ContentScale.Fit
        )
        ThumbnailState.Loading -> AssetIcon(fallbackIcon, alpha = 0.25f)
        ThumbnailState.Unavailable -> AssetIcon(fallbackIcon)
    }
}
