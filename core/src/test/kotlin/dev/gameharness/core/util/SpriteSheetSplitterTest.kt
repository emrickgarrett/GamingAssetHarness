package dev.gameharness.core.util

import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class SpriteSheetSplitterTest {

    /** Creates a test image filled with a single color. */
    private fun createTestImage(width: Int, height: Int, color: Color = Color.RED): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }

    /** Creates a test image with a unique color per grid cell for verification. */
    private fun createColorGridImage(cols: Int, rows: Int, tileWidth: Int, tileHeight: Int): BufferedImage {
        val img = BufferedImage(cols * tileWidth, rows * tileHeight, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                // Each cell gets a unique color based on row/col
                g.color = Color(col * 60 % 256, row * 80 % 256, (row + col) * 40 % 256)
                g.fillRect(col * tileWidth, row * tileHeight, tileWidth, tileHeight)
            }
        }
        g.dispose()
        return img
    }

    // ── analyze() ───────────────────────────────────────────────────────

    @Test
    fun `analyze returns correct grid for perfect fit`() {
        val result = SpriteSheetSplitter.analyze(128, 64, 32, 32)

        assertEquals(4, result.columns)
        assertEquals(2, result.rows)
        assertEquals(8, result.totalTiles)
        assertTrue(result.isExactFit)
        assertEquals(0, result.remainderX)
        assertEquals(0, result.remainderY)
        assertEquals(8, result.tiles.size)
    }

    @Test
    fun `analyze returns correct remainder for imperfect fit`() {
        val result = SpriteSheetSplitter.analyze(100, 50, 32, 32)

        assertEquals(3, result.columns)
        assertEquals(1, result.rows)
        assertEquals(3, result.totalTiles)
        assertFalse(result.isExactFit)
        assertEquals(4, result.remainderX)
        assertEquals(18, result.remainderY)
    }

    @Test
    fun `analyze returns zero tiles when tile larger than image`() {
        val result = SpriteSheetSplitter.analyze(16, 16, 64, 64)

        assertEquals(0, result.columns)
        assertEquals(0, result.rows)
        assertEquals(0, result.totalTiles)
        assertTrue(result.tiles.isEmpty())
    }

    @Test
    fun `analyze tiles have correct coordinates`() {
        val result = SpriteSheetSplitter.analyze(64, 64, 32, 32)

        assertEquals(4, result.tiles.size)
        val tile00 = result.tiles[0]
        assertEquals(0, tile00.row); assertEquals(0, tile00.col)
        assertEquals(0, tile00.x); assertEquals(0, tile00.y)

        val tile01 = result.tiles[1]
        assertEquals(0, tile01.row); assertEquals(1, tile01.col)
        assertEquals(32, tile01.x); assertEquals(0, tile01.y)

        val tile10 = result.tiles[2]
        assertEquals(1, tile10.row); assertEquals(0, tile10.col)
        assertEquals(0, tile10.x); assertEquals(32, tile10.y)

        val tile11 = result.tiles[3]
        assertEquals(1, tile11.row); assertEquals(1, tile11.col)
        assertEquals(32, tile11.x); assertEquals(32, tile11.y)
    }

    @Test
    fun `analyze rejects zero tile dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            SpriteSheetSplitter.analyze(100, 100, 0, 32)
        }
        assertFailsWith<IllegalArgumentException> {
            SpriteSheetSplitter.analyze(100, 100, 32, 0)
        }
    }

    // ── extractTile() ───────────────────────────────────────────────────

    @Test
    fun `extractTile produces independent copy`() {
        val source = createTestImage(64, 64, Color.BLUE)
        val tileInfo = SplitTileInfo(row = 0, col = 0, x = 0, y = 0, width = 32, height = 32)

        val tile = SpriteSheetSplitter.extractTile(source, tileInfo)

        assertEquals(32, tile.width)
        assertEquals(32, tile.height)

        // Modify tile and verify source is unaffected
        tile.setRGB(0, 0, Color.GREEN.rgb)
        assertEquals(Color.BLUE.rgb, source.getRGB(0, 0))
    }

    // ── splitAll() ──────────────────────────────────────────────────────

    @Test
    fun `splitAll extracts correct number of tiles`() {
        val source = createTestImage(64, 64)
        val tiles = SpriteSheetSplitter.splitAll(source, 32, 32)

        assertEquals(4, tiles.size)
        tiles.forEach { (info, img) ->
            assertEquals(32, img.width)
            assertEquals(32, img.height)
            assertEquals(32, info.width)
            assertEquals(32, info.height)
        }
    }

    @Test
    fun `splitAll preserves pixel data`() {
        val source = createColorGridImage(cols = 2, rows = 2, tileWidth = 16, tileHeight = 16)
        val tiles = SpriteSheetSplitter.splitAll(source, 16, 16)

        assertEquals(4, tiles.size)

        // Each tile should have a uniform color matching what we painted
        for ((info, img) in tiles) {
            // Sample the center pixel of the tile from the original
            val expectedRgb = source.getRGB(info.x + 8, info.y + 8)
            val actualRgb = img.getRGB(8, 8)
            assertEquals(expectedRgb, actualRgb, "Pixel mismatch at tile (${info.row}, ${info.col})")
        }
    }

    // ── tileToBytes() ───────────────────────────────────────────────────

    @Test
    fun `tileToBytes produces valid PNG that round-trips`() {
        val tile = createTestImage(32, 32, Color.RED)
        val bytes = SpriteSheetSplitter.tileToBytes(tile)

        assertTrue(bytes.isNotEmpty())

        // Read back and verify dimensions
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull(decoded)
        assertEquals(32, decoded.width)
        assertEquals(32, decoded.height)
    }

    @Test
    fun `tileToBytes preserves pixel colors`() {
        val tile = createTestImage(16, 16, Color.CYAN)
        val bytes = SpriteSheetSplitter.tileToBytes(tile)
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))

        // PNG is lossless, so the color should be exact
        val expected = Color.CYAN.rgb
        val actual = decoded.getRGB(8, 8)
        assertEquals(expected, actual)
    }

    // ── isFullyTransparent() ─────────────────────────────────────────

    @Test
    fun `isFullyTransparent returns true for blank ARGB tile`() {
        // BufferedImage with TYPE_INT_ARGB defaults to all zeros (fully transparent)
        val blank = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        assertTrue(SpriteSheetSplitter.isFullyTransparent(blank))
    }

    @Test
    fun `isFullyTransparent returns false for tile with any opaque pixel`() {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        // Set just one pixel to opaque red
        img.setRGB(8, 8, Color.RED.rgb)
        assertFalse(SpriteSheetSplitter.isFullyTransparent(img))
    }

    @Test
    fun `isFullyTransparent returns false for fully opaque tile`() {
        val img = createTestImage(16, 16, Color.BLUE)
        assertFalse(SpriteSheetSplitter.isFullyTransparent(img))
    }

    // ── removeBackgroundColor() ──────────────────────────────────────

    @Test
    fun `removeBackgroundColor makes matching pixels transparent`() {
        val tile = createTestImage(16, 16, Color.WHITE)
        val result = SpriteSheetSplitter.removeBackgroundColor(tile, Color.WHITE, tolerance = 0)

        // All pixels should now be fully transparent
        assertTrue(SpriteSheetSplitter.isFullyTransparent(result))
    }

    @Test
    fun `removeBackgroundColor leaves non-matching pixels untouched`() {
        val tile = createTestImage(16, 16, Color.RED)
        val result = SpriteSheetSplitter.removeBackgroundColor(tile, Color.WHITE, tolerance = 0)

        // Red pixels should remain opaque
        val rgb = result.getRGB(8, 8)
        val alpha = (rgb ushr 24) and 0xFF
        assertEquals(255, alpha, "Non-matching pixel should remain opaque")
        assertEquals(Color.RED.rgb, rgb)
    }

    @Test
    fun `removeBackgroundColor respects tolerance for near-matches`() {
        // Create a tile with a near-white color (RGB 250, 250, 250)
        val nearWhite = Color(250, 250, 250)
        val tile = createTestImage(16, 16, nearWhite)

        // Tolerance of 0 should NOT match (distance = 15)
        val strict = SpriteSheetSplitter.removeBackgroundColor(tile, Color.WHITE, tolerance = 0)
        assertFalse(SpriteSheetSplitter.isFullyTransparent(strict), "Strict tolerance should not match near-white")

        // Tolerance of 20 should match (distance = 5+5+5 = 15)
        val relaxed = SpriteSheetSplitter.removeBackgroundColor(tile, Color.WHITE, tolerance = 20)
        assertTrue(SpriteSheetSplitter.isFullyTransparent(relaxed), "Relaxed tolerance should match near-white")
    }

    @Test
    fun `removeBackgroundColor does not modify original`() {
        val tile = createTestImage(16, 16, Color.WHITE)
        val originalRgb = tile.getRGB(0, 0)

        SpriteSheetSplitter.removeBackgroundColor(tile, Color.WHITE, tolerance = 0)

        assertEquals(originalRgb, tile.getRGB(0, 0), "Original should not be modified")
    }

    @Test
    fun `removeBackgroundColor works with mixed content`() {
        // Create a 2x1 image: left pixel white, right pixel red
        val img = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, Color.WHITE.rgb)
        img.setRGB(1, 0, Color.RED.rgb)

        val result = SpriteSheetSplitter.removeBackgroundColor(img, Color.WHITE, tolerance = 0)

        // Left pixel should be transparent
        assertEquals(0, (result.getRGB(0, 0) ushr 24) and 0xFF, "White pixel should become transparent")
        // Right pixel should remain opaque red
        assertEquals(Color.RED.rgb, result.getRGB(1, 0), "Red pixel should be unchanged")
    }
}
