package dev.gameharness.gui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.Dimension
import javax.imageio.ImageIO

/**
 * Provides the AWT Window reference to any descendant composable,
 * needed for native dialogs like JFileChooser.
 */
val LocalAwtWindow = compositionLocalOf<java.awt.Window> {
    error("No AWT Window provided")
}

/** Application entry point. Creates the main window with icon, minimum size, and the AWT window CompositionLocal. */
fun main() = application {
    val iconBitmap = javaClass.getResourceAsStream("/icon.png")?.let { stream ->
        BitmapPainter(ImageIO.read(stream).toComposeImageBitmap())
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Game Developer Harness v${BuildInfo.version}",
        state = WindowState(width = 1400.dp, height = 900.dp),
        icon = iconBitmap
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(900, 600)
        }
        CompositionLocalProvider(LocalAwtWindow provides window) {
            App()
        }
    }
}
