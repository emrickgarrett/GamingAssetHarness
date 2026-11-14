package dev.gameharness.gui.thumbs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread

/** Where a card's thumbnail stands. */
sealed interface ThumbnailState {
    /** Being made, or waiting its turn. */
    data object Loading : ThumbnailState
    /** The file is missing, unreadable, or nothing can draw it. */
    data object Unavailable : ThumbnailState
    class Ready(val bitmap: ImageBitmap) : ThumbnailState
}

/**
 * The application's thumbnail store.
 *
 * Its disk cache lives beside the settings (`~/.gameharness/cache/thumbnails`).
 * `-Dgameharness.thumbnailCache=<dir>` moves it, and `=off` keeps thumbnails in
 * memory only; tests and the benchmark use both so they never write there.
 */
object Thumbnails {
    val store: ThumbnailStore by lazy {
        val configured = System.getProperty("gameharness.thumbnailCache")
        val dir: Path? = when {
            configured == null -> Paths.get(System.getProperty("user.home"), ".gameharness", "cache", "thumbnails")
            configured.equals("off", ignoreCase = true) -> null
            else -> Paths.get(configured)
        }
        ThumbnailStore(dir).also { store ->
            ModelThumbnails.install(store)
            thread(name = "thumbnail-cache-prune", isDaemon = true, priority = Thread.MIN_PRIORITY) { store.pruneDisk() }
        }
    }
}

/** The store composables draw thumbnails from; replaced in tests. */
val LocalThumbnailStore = staticCompositionLocalOf { Thumbnails.store }

/**
 * The thumbnail of the file at [path].
 *
 * Starts as [ThumbnailState.Ready] when the picture is already in memory, so a
 * card scrolled back into view, or a folder opened a second time, never shows
 * a placeholder. Otherwise it loads in the background; leaving the screen
 * cancels a thumbnail that had not started yet.
 */
@Composable
fun rememberThumbnail(path: String, kind: ThumbnailKind): ThumbnailState {
    val store = LocalThumbnailStore.current
    var state by remember(path, store) {
        mutableStateOf(store.cached(path)?.let { ThumbnailState.Ready(it) } ?: ThumbnailState.Loading)
    }
    LaunchedEffect(path, store) {
        val bitmap = store.load(path, kind)
        val current = state
        state = when {
            bitmap == null -> ThumbnailState.Unavailable
            current is ThumbnailState.Ready && current.bitmap === bitmap -> current
            else -> ThumbnailState.Ready(bitmap)
        }
    }
    return state
}
