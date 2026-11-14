package dev.gameharness.gui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.gameharness.core.model.ChatMessage
import dev.gameharness.core.model.ChatRole
import dev.gameharness.core.util.SpriteSheetSplitter
import dev.gameharness.gui.theme.GameHarnessTheme
import dev.gameharness.gui.thumbs.LocalThumbnailStore
import dev.gameharness.gui.thumbs.ThumbnailStore
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two places outside the asset grid that used to decode an image on the UI
 * thread: a chat bubble's attachment, and the split-sheet dialog's previews.
 */
class ComponentsTest {

    /** How many pixels of [image] are (nearly) pure green, the color of the test attachment and of nothing in the theme. */
    private fun greenPixels(image: Image): Int {
        val bitmap = Bitmap.makeFromImage(image)
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val c = bitmap.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 230 && r < 30 && b < 30) count++
            }
        }
        bitmap.close()
        return count
    }

    @Test
    fun `a chat attachment is drawn from a thumbnail made in the background`(@TempDir tmp: Path) {
        val attachment = tmp.resolve("reference.png").toFile()
        val picture = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB)
        picture.createGraphics().apply { color = Color(0, 255, 0); fillRect(0, 0, 600, 400); dispose() }
        ImageIO.write(picture, "png", attachment)

        val store = ThumbnailStore(null)
        val message = ChatMessage(ChatRole.USER, "like this one", attachmentPaths = listOf(attachment.path))
        val scene = ImageComposeScene(600, 240, Density(1f)) {
            CompositionLocalProvider(LocalThumbnailStore provides store) {
                GameHarnessTheme(darkTheme = true) { ChatBubble(message) }
            }
        }
        try {
            // Composing the bubble must not have read the file: the first frame has no picture yet.
            assertEquals(0, greenPixels(scene.render(0)), "the attachment was decoded while composing")

            val deadline = System.currentTimeMillis() + 10_000
            var green = 0
            while (green == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                green = greenPixels(scene.render(System.nanoTime()))
            }
            // drawn at most 120 x 80; a 3:2 picture fills that box
            assertTrue(green > 5_000, "the attachment should be showing, found $green green pixels")
            assertEquals(320, store.cached(attachment.path)?.width, "and it is the store's thumbnail, not the full image")
        } finally {
            scene.close()
        }
    }

    /** A 96 x 32 sheet of three tiles: red, fully transparent, white. */
    private fun threeTileSheet(): BufferedImage {
        val sheet = BufferedImage(96, 32, BufferedImage.TYPE_INT_ARGB)
        sheet.createGraphics().apply {
            color = Color.RED; fillRect(0, 0, 32, 32)
            color = Color.WHITE; fillRect(64, 0, 32, 32)
            dispose()
        }
        return sheet
    }

    @Test
    fun `the split preview counts what the filters keep`() = runBlocking {
        val sheet = threeTileSheet()
        val tiles = SpriteSheetSplitter.analyze(sheet.width, sheet.height, 32, 32).tiles
        assertEquals(3, tiles.size)

        val (allPreviews, all) = buildSplitPreview(sheet, tiles, skipEmpty = false, removeBgColor = null, bgTolerance = 30)
        assertEquals(3, all)
        assertEquals(3, allPreviews.size)

        val (previews, kept) = buildSplitPreview(sheet, tiles, skipEmpty = true, removeBgColor = null, bgTolerance = 30)
        assertEquals(2, kept, "the transparent tile is dropped")
        assertEquals(listOf(0, 2), previews.map { it.first.col })

        // taking white out as background empties the white tile as well
        val (_, withoutWhite) = buildSplitPreview(sheet, tiles, skipEmpty = true, removeBgColor = Color.WHITE, bgTolerance = 30)
        assertEquals(1, withoutWhite)

        // background removal alone removes pixels, not tiles
        val (_, removedOnly) = buildSplitPreview(sheet, tiles, skipEmpty = false, removeBgColor = Color.WHITE, bgTolerance = 30)
        assertEquals(3, removedOnly)
    }

    @Test
    fun `a sheet with more tiles than can be previewed is still counted in full`() = runBlocking {
        // 100 opaque 4 x 4 tiles, then one transparent row of 10
        val sheet = BufferedImage(40, 44, BufferedImage.TYPE_INT_ARGB)
        sheet.createGraphics().apply { color = Color.BLUE; fillRect(0, 0, 40, 40); dispose() }
        val tiles = SpriteSheetSplitter.analyze(sheet.width, sheet.height, 4, 4).tiles
        assertEquals(110, tiles.size)

        val (previews, kept) = buildSplitPreview(sheet, tiles, skipEmpty = true, removeBgColor = null, bgTolerance = 30)
        assertEquals(MAX_TILE_PREVIEWS, previews.size)
        assertEquals(100, kept, "tiles past the preview limit are filtered too")

        val (_, unfiltered) = buildSplitPreview(sheet, tiles, skipEmpty = false, removeBgColor = null, bgTolerance = 30)
        assertEquals(110, unfiltered)
    }
}
