package dev.gameharness.gui.util

import java.awt.Window
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Opens a native image file picker dialog and returns the selected file path, or null if cancelled.
 * Uses the system look-and-feel for native appearance on Windows, macOS, and Linux.
 *
 * @param parent AWT Window to anchor the dialog (from Compose's Window scope)
 * @param title Dialog title
 * @return Selected image file path, or null if the user cancelled
 */
fun pickImage(parent: Window?, title: String = "Select Reference Image"): Path? {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {
        // Fall back to default look-and-feel
    }

    var result: Path? = null
    val showChooser = Runnable {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = FileNameExtensionFilter(
                "Image files (*.png, *.jpg, *.jpeg, *.webp, *.bmp)",
                "png", "jpg", "jpeg", "webp", "bmp"
            )
        }
        val returnVal = chooser.showOpenDialog(parent)
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            result = chooser.selectedFile.toPath()
        }
    }

    if (SwingUtilities.isEventDispatchThread()) {
        showChooser.run()
    } else {
        SwingUtilities.invokeAndWait(showChooser)
    }
    return result
}
