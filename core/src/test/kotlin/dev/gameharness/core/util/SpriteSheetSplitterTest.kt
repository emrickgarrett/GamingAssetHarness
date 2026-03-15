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

    // -- removeBackgroundFloodFill() ------------------------------------------

    @Test
    fun `removeBackgroundFloodFill makes border-connected bg pixels transparent`() {
        val green = Color(0x00, 0xB1, 0x40)
        val img = createTestImage(4, 4, green)
        val result = SpriteSheetSplitter.removeBackgroundFloodFill(img, green, tolerance = 0)
        assertTrue(SpriteSheetSplitter.isFullyTransparent(result))
    }

    @Test
    fun `removeBackgroundFloodFill preserves interior non-bg pixels`() {
        val green = Color(0x00, 0xB1, 0x40)
        val img = createTestImage(5, 5, green)
        img.setRGB(2, 2, Color.RED.rgb)

        val result = SpriteSheetSplitter.removeBackgroundFloodFill(img, green, tolerance = 0)

        // Center pixel should remain opaque red
        assertEquals(Color.RED.rgb, result.getRGB(2, 2))
        // Border pixels should be transparent
        assertEquals(0, (result.getRGB(0, 0) ushr 24) and 0xFF)
    }

    @Test
    fun `removeBackgroundFloodFill does not remove interior green behind sprite wall`() {
        // 5x5 image: green border, red wall at (1,1)-(3,3), green center at (2,2)
        // The center green pixel is NOT connected to the border through green
        val green = Color(0x00, 0xB1, 0x40)
        val img = createTestImage(5, 5, green)
        // Paint a red wall around the center
        for (x in 1..3) {
            for (y in 1..3) {
                img.setRGB(x, y, Color.RED.rgb)
            }
        }
        // Put green back in the very center
        img.setRGB(2, 2, green.rgb)

        val result = SpriteSheetSplitter.removeBackgroundFloodFill(img, green, tolerance = 0)

        // Border green should be removed
        assertEquals(0, (result.getRGB(0, 0) ushr 24) and 0xFF, "Border green should be removed")
        // Interior green at (2,2) should remain (not connected to border)
        val centerAlpha = (result.getRGB(2, 2) ushr 24) and 0xFF
        assertEquals(255, centerAlpha, "Interior green should be preserved")
        // Red wall should remain
        assertEquals(Color.RED.rgb, result.getRGB(1, 1))
    }

    @Test
    fun `removeBackgroundFloodFill respects tolerance for noisy backgrounds`() {
        val exactGreen = Color(0x00, 0xB1, 0x40)
        val noisyGreen = Color(0x10, 0xA0, 0x50) // Manhattan distance = 16+17+16 = 49
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 3) for (x in 0 until 3) img.setRGB(x, y, noisyGreen.rgb)
        img.setRGB(1, 1, Color.RED.rgb)

        // Tolerance 30 should NOT match noisy green (distance=49)
        val strict = SpriteSheetSplitter.removeBackgroundFloodFill(img, exactGreen, tolerance = 30)
        val strictCornerAlpha = (strict.getRGB(0, 0) ushr 24) and 0xFF
        assertEquals(255, strictCornerAlpha, "Low tolerance should not match noisy green")

        // Tolerance 60 SHOULD match noisy green (distance=49 <= 60)
        val relaxed = SpriteSheetSplitter.removeBackgroundFloodFill(img, exactGreen, tolerance = 60)
        val relaxedCornerAlpha = (relaxed.getRGB(0, 0) ushr 24) and 0xFF
        assertEquals(0, relaxedCornerAlpha, "High tolerance should match noisy green")
        // Red center should still remain
        assertEquals(Color.RED.rgb, relaxed.getRGB(1, 1))
    }

    @Test
    fun `removeBackgroundFloodFill does not modify original image`() {
        val green = Color(0x00, 0xB1, 0x40)
        val img = createTestImage(4, 4, green)
        val originalRgb = img.getRGB(0, 0)

        SpriteSheetSplitter.removeBackgroundFloodFill(img, green, tolerance = 0)

        assertEquals(originalRgb, img.getRGB(0, 0), "Original should not be modified")
    }

    @Test
    fun `removeBackgroundFloodFill handles image with no background pixels`() {
        val img = createTestImage(4, 4, Color.RED)
        val result = SpriteSheetSplitter.removeBackgroundFloodFill(
            img, Color(0x00, 0xB1, 0x40), tolerance = 0
        )
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(Color.RED.rgb, result.getRGB(x, y))
            }
        }
    }

    @Test
    fun `removeBackgroundFloodFill handles 1x1 image`() {
        val green = Color(0x00, 0xB1, 0x40)
        val img = createTestImage(1, 1, green)
        val result = SpriteSheetSplitter.removeBackgroundFloodFill(img, green, tolerance = 0)
        assertTrue(SpriteSheetSplitter.isFullyTransparent(result))
    }

    @Test
    fun `removeBackgroundFloodFill treats transparent pixels as connectable background`() {
        val green = Color(0x00, 0xB1, 0x40)
        val img = BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB)
        // Top row: transparent
        for (x in 0 until 3) img.setRGB(x, 0, 0x00000000)
        // Middle row: green, red, green
        img.setRGB(0, 1, green.rgb)
        img.setRGB(1, 1, Color.RED.rgb)
        img.setRGB(2, 1, green.rgb)
        // Bottom row: green
        for (x in 0 until 3) img.setRGB(x, 2, green.rgb)

        val result = SpriteSheetSplitter.removeBackgroundFloodFill(img, green, tolerance = 0)

        // Green pixels connected to border via transparent should be removed
        assertEquals(0, (result.getRGB(0, 1) ushr 24) and 0xFF, "Left green connected to border via transparent")
        assertEquals(0, (result.getRGB(2, 1) ushr 24) and 0xFF, "Right green connected to border via transparent")
        // Red center should remain
        assertEquals(Color.RED.rgb, result.getRGB(1, 1), "Red center should be preserved")
    }

    // -- defringeEdges() ------------------------------------------------------

    @Test
    fun `defringeEdges removes edge pixels within tolerance of bg color`() {
        val green = Color(0x00, 0xB1, 0x40)
        // 5x5 transparent image with a 3x3 near-green block in the center
        val nearGreen = Color(0x20, 0xA0, 0x50) // Manhattan distance = 32+17+16 = 65
        val img = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 5) for (x in 0 until 5) img.setRGB(x, y, 0x00000000)
        for (y in 1..3) for (x in 1..3) img.setRGB(x, y, nearGreen.rgb)
        // Set center to red (non-green)
        img.setRGB(2, 2, Color.RED.rgb)

        val result = SpriteSheetSplitter.defringeEdges(img, green, tolerance = 120)

        // The 8 edge pixels of the 3x3 block should be removed (distance 65 <= 120)
        assertEquals(0, (result.getRGB(1, 1) ushr 24) and 0xFF, "Edge near-green should be removed")
        assertEquals(0, (result.getRGB(2, 1) ushr 24) and 0xFF, "Top edge should be removed")
        assertEquals(0, (result.getRGB(3, 1) ushr 24) and 0xFF, "Edge near-green should be removed")
        // Red center is not adjacent to transparent in original, so it stays
        assertEquals(Color.RED.rgb, result.getRGB(2, 2), "Red center should remain")
    }

    @Test
    fun `defringeEdges preserves edge pixels outside tolerance`() {
        val green = Color(0x00, 0xB1, 0x40)
        // 5x5 transparent image with a 3x3 red block in the center
        val img = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 5) for (x in 0 until 5) img.setRGB(x, y, 0x00000000)
        for (y in 1..3) for (x in 1..3) img.setRGB(x, y, Color.RED.rgb)

        val result = SpriteSheetSplitter.defringeEdges(img, green, tolerance = 120)

        // Red has distance ~496 from green — all 9 pixels should survive
        for (y in 1..3) {
            for (x in 1..3) {
                assertEquals(Color.RED.rgb, result.getRGB(x, y), "Red pixels should be preserved")
            }
        }
    }

    @Test
    fun `defringeEdges does not remove interior pixels even if they match bg color`() {
        val green = Color(0x00, 0xB1, 0x40)
        // 7x7 transparent image with a 5x5 near-green block
        val nearGreen = Color(0x10, 0xA5, 0x45) // Close to green
        val img = BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 7) for (x in 0 until 7) img.setRGB(x, y, 0x00000000)
        for (y in 1..5) for (x in 1..5) img.setRGB(x, y, nearGreen.rgb)

        val result = SpriteSheetSplitter.defringeEdges(img, green, tolerance = 120)

        // Outer ring (edge pixels) of the 5x5 block should be removed
        assertEquals(0, (result.getRGB(1, 1) ushr 24) and 0xFF, "Outer ring should be removed")
        // Inner 3x3 should remain — they are NOT adjacent to transparent in the input
        val innerAlpha = (result.getRGB(3, 3) ushr 24) and 0xFF
        assertEquals(255, innerAlpha, "Interior pixels should be preserved")
    }

    @Test
    fun `defringeEdges does not cascade beyond one layer`() {
        val green = Color(0x00, 0xB1, 0x40)
        // 7x7 transparent image with 5x5 near-green block
        val nearGreen = Color(0x10, 0xA5, 0x45)
        val img = BufferedImage(7, 7, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 7) for (x in 0 until 7) img.setRGB(x, y, 0x00000000)
        for (y in 1..5) for (x in 1..5) img.setRGB(x, y, nearGreen.rgb)

        val result = SpriteSheetSplitter.defringeEdges(img, green, tolerance = 120)

        // Only the outer ring (16 pixels) should be removed
        // The second ring (pixels at distance 2 from border like (2,2)) should remain
        val secondRingAlpha = (result.getRGB(2, 2) ushr 24) and 0xFF
        assertEquals(255, secondRingAlpha, "Second ring should NOT be removed (no cascading)")
    }

    @Test
    fun `defringeEdges handles fully transparent image`() {
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) img.setRGB(x, y, 0x00000000)

        val result = SpriteSheetSplitter.defringeEdges(img, Color(0x00, 0xB1, 0x40), tolerance = 120)
        assertTrue(SpriteSheetSplitter.isFullyTransparent(result))
    }

    @Test
    fun `defringeEdges does not modify original image`() {
        val green = Color(0x00, 0xB1, 0x40)
        val img = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 5) for (x in 0 until 5) img.setRGB(x, y, 0x00000000)
        for (y in 1..3) for (x in 1..3) img.setRGB(x, y, green.rgb)
        val originalRgb = img.getRGB(2, 2)

        SpriteSheetSplitter.defringeEdges(img, green, tolerance = 120)

        assertEquals(originalRgb, img.getRGB(2, 2), "Original should not be modified")
    }

    @Test
    fun `defringeEdges two passes removes two pixel layers of fringe`() {
        val green = Color(0x00, 0xB1, 0x40)
        // 9x9 transparent image with 7x7 near-green block in center (rows/cols 1-7)
        val nearGreen = Color(0x10, 0xA5, 0x45)
        val img = BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 9) for (x in 0 until 9) img.setRGB(x, y, 0x00000000)
        for (y in 1..7) for (x in 1..7) img.setRGB(x, y, nearGreen.rgb)

        // Single pass: removes outer ring (layer 1), inner 5x5 (rows/cols 2-6) remains
        val pass1 = SpriteSheetSplitter.defringeEdges(img, green, tolerance = 120)
        val pass1Layer1Alpha = (pass1.getRGB(1, 1) ushr 24) and 0xFF
        assertEquals(0, pass1Layer1Alpha, "First pass should remove layer 1")
        val pass1Layer2Alpha = (pass1.getRGB(2, 2) ushr 24) and 0xFF
        assertEquals(255, pass1Layer2Alpha, "First pass should NOT remove layer 2")

        // Second pass: removes next ring (layer 2), inner 3x3 (rows/cols 3-5) remains
        val pass2 = SpriteSheetSplitter.defringeEdges(pass1, green, tolerance = 120)
        val pass2Layer2Alpha = (pass2.getRGB(2, 2) ushr 24) and 0xFF
        assertEquals(0, pass2Layer2Alpha, "Second pass should remove layer 2")
        val pass2Layer3Alpha = (pass2.getRGB(3, 3) ushr 24) and 0xFF
        assertEquals(255, pass2Layer3Alpha, "Second pass should NOT remove layer 3")
    }

    // ── trimTransparent() ─────────────────────────────────────────────

    @Test
    fun `trimTransparent crops uniform transparent border`() {
        // 10x10 image with a 4x4 red block at (3,3)-(6,6)
        val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..6) for (x in 3..6) img.setRGB(x, y, Color.RED.rgb)

        val result = SpriteSheetSplitter.trimTransparent(img)

        assertTrue(result.wasTrimmed)
        assertEquals(10, result.originalWidth)
        assertEquals(10, result.originalHeight)
        assertEquals(4, result.trimmedWidth)
        assertEquals(4, result.trimmedHeight)
        assertEquals(4, result.image.width)
        assertEquals(4, result.image.height)
        // All pixels in the cropped image should be red
        for (y in 0 until 4) for (x in 0 until 4) {
            assertEquals(Color.RED.rgb, result.image.getRGB(x, y))
        }
    }

    @Test
    fun `trimTransparent returns unchanged for image with no transparent border`() {
        val img = createTestImage(8, 8, Color.BLUE)
        val result = SpriteSheetSplitter.trimTransparent(img)

        assertFalse(result.wasTrimmed)
        assertEquals(8, result.originalWidth)
        assertEquals(8, result.originalHeight)
        assertEquals(8, result.trimmedWidth)
        assertEquals(8, result.trimmedHeight)
        assertEquals(8, result.image.width)
        assertEquals(8, result.image.height)
    }

    @Test
    fun `trimTransparent returns 1x1 for fully transparent image`() {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)

        val result = SpriteSheetSplitter.trimTransparent(img)

        assertTrue(result.wasTrimmed)
        assertEquals(16, result.originalWidth)
        assertEquals(16, result.originalHeight)
        assertEquals(1, result.trimmedWidth)
        assertEquals(1, result.trimmedHeight)
        assertTrue(SpriteSheetSplitter.isFullyTransparent(result.image))
    }

    @Test
    fun `trimTransparent handles asymmetric margins`() {
        // 20x10 image with a single pixel at (2, 7) — asymmetric borders
        val img = BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(2, 7, Color.GREEN.rgb)

        val result = SpriteSheetSplitter.trimTransparent(img)

        assertTrue(result.wasTrimmed)
        assertEquals(1, result.trimmedWidth)
        assertEquals(1, result.trimmedHeight)
        assertEquals(Color.GREEN.rgb, result.image.getRGB(0, 0))
    }

    @Test
    fun `trimTransparent does not modify original image`() {
        val img = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..6) for (x in 3..6) img.setRGB(x, y, Color.RED.rgb)
        val originalPixel = img.getRGB(5, 5)

        SpriteSheetSplitter.trimTransparent(img)

        assertEquals(originalPixel, img.getRGB(5, 5), "Original image should not be modified")
        // Transparent corners should still be transparent
        assertEquals(0, (img.getRGB(0, 0) ushr 24) and 0xFF)
    }

    @Test
    fun `defringeEdges works with magenta chroma key`() {
        val magenta = Color(0xFF, 0x00, 0xFF)
        val nearMagenta = Color(0xE0, 0x20, 0xE0) // Manhattan distance = 31+32+31 = 94
        val img = BufferedImage(5, 5, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 5) for (x in 0 until 5) img.setRGB(x, y, 0x00000000)
        for (y in 1..3) for (x in 1..3) img.setRGB(x, y, nearMagenta.rgb)

        val result = SpriteSheetSplitter.defringeEdges(img, magenta, tolerance = 120)

        // Edge near-magenta pixels should be removed (distance 94 <= 120)
        assertEquals(0, (result.getRGB(1, 1) ushr 24) and 0xFF, "Near-magenta edge should be removed")
    }
}
