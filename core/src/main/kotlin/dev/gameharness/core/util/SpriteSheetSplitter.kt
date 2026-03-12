package dev.gameharness.core.util

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Metadata for a single tile in a sprite sheet split.
 *
 * @property row Zero-based row index (top to bottom).
 * @property col Zero-based column index (left to right).
 * @property x Pixel x-coordinate of the tile's top-left corner.
 * @property y Pixel y-coordinate of the tile's top-left corner.
 * @property width Tile width in pixels.
 * @property height Tile height in pixels.
 */
data class SplitTileInfo(
    val row: Int,
    val col: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * Analysis result describing how a sprite sheet would be split at a given tile size.
 *
 * @property tiles Metadata for each tile that would be produced.
 * @property columns Number of tile columns.
 * @property rows Number of tile rows.
 * @property totalTiles Total number of tiles ([columns] * [rows]).
 * @property isExactFit True if the image dimensions are perfectly divisible by the tile size.
 * @property remainderX Pixels left over horizontally (not included in any tile).
 * @property remainderY Pixels left over vertically (not included in any tile).
 */
data class SplitResult(
    val tiles: List<SplitTileInfo>,
    val columns: Int,
    val rows: Int,
    val totalTiles: Int,
    val isExactFit: Boolean,
    val remainderX: Int,
    val remainderY: Int
)

/**
 * Utility for splitting sprite sheet images into individual tiles.
 *
 * Uses a grid-based approach: the user specifies tile dimensions, and the image
 * is sliced into a uniform grid. Edge pixels that don't form a complete tile are
 * discarded. All operations are pure functions with no side effects.
 */
object SpriteSheetSplitter {

    /**
     * Analyzes how an image would split at the given tile dimensions without
     * touching any pixel data. Use this for live preview updates in the UI.
     *
     * @return [SplitResult] with grid metadata, or a result with 0 tiles if
     *     the tile size exceeds the image dimensions.
     */
    fun analyze(imageWidth: Int, imageHeight: Int, tileWidth: Int, tileHeight: Int): SplitResult {
        require(tileWidth > 0) { "Tile width must be positive" }
        require(tileHeight > 0) { "Tile height must be positive" }

        val columns = imageWidth / tileWidth
        val rows = imageHeight / tileHeight
        val remainderX = imageWidth % tileWidth
        val remainderY = imageHeight % tileHeight

        val tiles = buildList {
            for (row in 0 until rows) {
                for (col in 0 until columns) {
                    add(
                        SplitTileInfo(
                            row = row,
                            col = col,
                            x = col * tileWidth,
                            y = row * tileHeight,
                            width = tileWidth,
                            height = tileHeight
                        )
                    )
                }
            }
        }

        return SplitResult(
            tiles = tiles,
            columns = columns,
            rows = rows,
            totalTiles = columns * rows,
            isExactFit = remainderX == 0 && remainderY == 0,
            remainderX = remainderX,
            remainderY = remainderY
        )
    }

    /**
     * Extracts a single tile from the source image as an independent [BufferedImage].
     *
     * The returned image is a deep copy — modifying it does not affect the source,
     * and the source can be garbage-collected without affecting the tile.
     */
    fun extractTile(source: BufferedImage, tile: SplitTileInfo): BufferedImage {
        val subImage = source.getSubimage(tile.x, tile.y, tile.width, tile.height)
        // Copy to a new independent BufferedImage (getSubimage shares the raster)
        val copy = BufferedImage(tile.width, tile.height, source.type.coerceAtLeast(BufferedImage.TYPE_INT_ARGB))
        val g = copy.createGraphics()
        g.drawImage(subImage, 0, 0, null)
        g.dispose()
        return copy
    }

    /**
     * Splits the entire source image into tiles at the given dimensions.
     *
     * @return a list of (tile metadata, extracted image) pairs, ordered left-to-right
     *     then top-to-bottom.
     */
    fun splitAll(source: BufferedImage, tileWidth: Int, tileHeight: Int): List<Pair<SplitTileInfo, BufferedImage>> {
        val result = analyze(source.width, source.height, tileWidth, tileHeight)
        return result.tiles.map { tile -> tile to extractTile(source, tile) }
    }

    /**
     * Checks whether every pixel in the tile is fully transparent (alpha = 0).
     *
     * Returns early on the first non-transparent pixel for performance.
     */
    fun isFullyTransparent(tile: BufferedImage): Boolean {
        for (y in 0 until tile.height) {
            for (x in 0 until tile.width) {
                val alpha = tile.getRGB(x, y) ushr 24
                if (alpha != 0) return false
            }
        }
        return true
    }

    /**
     * Creates a copy of the tile with all pixels matching [bgColor] (within
     * [tolerance]) set to fully transparent.
     *
     * Uses Manhattan distance in RGB space: `|r1-r2| + |g1-g2| + |b1-b2|`.
     * A tolerance of 0 matches only the exact color; higher values catch
     * near-matches caused by anti-aliasing or compression.
     *
     * The original image is not modified.
     *
     * @param tile the source tile image
     * @param bgColor the background color to remove
     * @param tolerance maximum combined RGB distance (0–765, default 30)
     * @return a new [BufferedImage] with matching pixels made transparent
     */
    fun removeBackgroundColor(tile: BufferedImage, bgColor: Color, tolerance: Int = 30): BufferedImage {
        val copy = BufferedImage(tile.width, tile.height, BufferedImage.TYPE_INT_ARGB)
        val bgR = bgColor.red
        val bgG = bgColor.green
        val bgB = bgColor.blue

        for (y in 0 until tile.height) {
            for (x in 0 until tile.width) {
                val rgb = tile.getRGB(x, y)
                val a = (rgb ushr 24) and 0xFF
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF

                val distance = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)

                if (a > 0 && distance <= tolerance) {
                    // Make this pixel transparent
                    copy.setRGB(x, y, 0x00000000)
                } else {
                    copy.setRGB(x, y, rgb)
                }
            }
        }
        return copy
    }

    /**
     * Encodes a tile [BufferedImage] as PNG bytes.
     */
    fun tileToBytes(tile: BufferedImage): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(tile, "png", baos)
        return baos.toByteArray()
    }
}
