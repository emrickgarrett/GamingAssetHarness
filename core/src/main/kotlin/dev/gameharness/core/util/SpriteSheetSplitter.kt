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
 * Result of trimming transparent borders from a sprite image.
 *
 * @property image The trimmed image (or copy of original if nothing was trimmed).
 * @property originalWidth Width of the source image before trimming.
 * @property originalHeight Height of the source image before trimming.
 * @property trimmedWidth Width after trimming.
 * @property trimmedHeight Height after trimming.
 * @property wasTrimmed True if any transparent borders were removed.
 */
data class TrimResult(
    val image: BufferedImage,
    val originalWidth: Int,
    val originalHeight: Int,
    val trimmedWidth: Int,
    val trimmedHeight: Int,
    val wasTrimmed: Boolean
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
     * Creates a copy of the image with background pixels made transparent using
     * flood-fill from the image borders.
     *
     * Unlike [removeBackgroundColor] which removes ALL pixels matching the background
     * color regardless of position, this method only removes pixels that are:
     * 1. Within [tolerance] of [bgColor] (Manhattan distance in RGB), AND
     * 2. Connected to the image border through a chain of matching pixels.
     *
     * This is safer for sprites that contain internal pixels similar to the background
     * color (e.g., a character wearing green on a green background). Only the
     * exterior connected background region is removed.
     *
     * Uses BFS (breadth-first search) flood fill seeded from all border pixels that
     * match the background color. Already-transparent pixels are treated as background
     * for connectivity purposes.
     *
     * The original image is not modified.
     *
     * @param image the source image
     * @param bgColor the background color to remove
     * @param tolerance maximum combined RGB distance (0–765, default 60)
     * @return a new [BufferedImage] with border-connected background pixels made transparent
     */
    fun removeBackgroundFloodFill(
        image: BufferedImage,
        bgColor: Color,
        tolerance: Int = 60
    ): BufferedImage {
        val w = image.width
        val h = image.height
        val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        // Copy all pixels first
        for (y in 0 until h) {
            for (x in 0 until w) {
                copy.setRGB(x, y, image.getRGB(x, y))
            }
        }

        val bgR = bgColor.red
        val bgG = bgColor.green
        val bgB = bgColor.blue
        val visited = BooleanArray(w * h)

        // Check if a pixel matches the background color within tolerance
        fun matchesBg(x: Int, y: Int): Boolean {
            val rgb = image.getRGB(x, y)
            val a = (rgb ushr 24) and 0xFF
            if (a == 0) return true // Already transparent, treat as background
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            return abs(r - bgR) + abs(g - bgG) + abs(b - bgB) <= tolerance
        }

        // BFS queue using flat indices (y * w + x)
        val queue = ArrayDeque<Int>()

        fun tryEnqueue(x: Int, y: Int) {
            val idx = y * w + x
            if (!visited[idx] && matchesBg(x, y)) {
                visited[idx] = true
                queue.addLast(idx)
            }
        }

        // Seed from all border pixels
        for (x in 0 until w) {
            tryEnqueue(x, 0)
            tryEnqueue(x, h - 1)
        }
        for (y in 1 until h - 1) {
            tryEnqueue(0, y)
            tryEnqueue(w - 1, y)
        }

        // BFS flood fill
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % w
            val y = idx / w
            copy.setRGB(x, y, 0x00000000)

            // Enqueue 4-connected neighbors
            if (x > 0) tryEnqueue(x - 1, y)
            if (x < w - 1) tryEnqueue(x + 1, y)
            if (y > 0) tryEnqueue(x, y - 1)
            if (y < h - 1) tryEnqueue(x, y + 1)
        }

        return copy
    }

    /**
     * Removes anti-aliased fringe pixels along the edges of a sprite that has
     * already had its background removed (e.g., by [removeBackgroundFloodFill]).
     *
     * After flood-fill background removal, edge pixels of the sprite may still
     * contain a tint of the background color due to anti-aliasing blending.
     * This method finds all opaque pixels adjacent to at least one transparent
     * pixel and, if that edge pixel is within [tolerance] of [bgColor], makes
     * it transparent.
     *
     * This is a single-pass operation — edge adjacency is determined from the
     * **input** image, so at most one pixel layer is removed (no cascading).
     *
     * The original image is not modified.
     *
     * @param image the source image (typically output of [removeBackgroundFloodFill])
     * @param bgColor the background color to check edge pixels against
     * @param tolerance maximum combined RGB distance for edge pixels (0–765, default 120)
     * @return a new [BufferedImage] with fringe pixels made transparent
     */
    fun defringeEdges(
        image: BufferedImage,
        bgColor: Color,
        tolerance: Int = 120
    ): BufferedImage {
        val w = image.width
        val h = image.height
        val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        val bgR = bgColor.red
        val bgG = bgColor.green
        val bgB = bgColor.blue

        // Check if a pixel in the input image is transparent
        fun isTransparent(x: Int, y: Int): Boolean =
            ((image.getRGB(x, y) ushr 24) and 0xFF) == 0

        // Check if an opaque pixel is adjacent to at least one transparent pixel
        fun isEdgePixel(x: Int, y: Int): Boolean {
            if (x > 0 && isTransparent(x - 1, y)) return true
            if (x < w - 1 && isTransparent(x + 1, y)) return true
            if (y > 0 && isTransparent(x, y - 1)) return true
            if (y < h - 1 && isTransparent(x, y + 1)) return true
            return false
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val rgb = image.getRGB(x, y)
                val a = (rgb ushr 24) and 0xFF

                if (a > 0 && isEdgePixel(x, y)) {
                    // Edge pixel — check if it's too close to the bg color
                    val r = (rgb ushr 16) and 0xFF
                    val g = (rgb ushr 8) and 0xFF
                    val b = rgb and 0xFF
                    val distance = abs(r - bgR) + abs(g - bgG) + abs(b - bgB)
                    if (distance <= tolerance) {
                        copy.setRGB(x, y, 0x00000000)
                    } else {
                        copy.setRGB(x, y, rgb)
                    }
                } else {
                    copy.setRGB(x, y, rgb)
                }
            }
        }

        return copy
    }

    /**
     * Removes residual chroma key contamination from the edge region of a sprite
     * whose background has already been removed (e.g., by [removeBackgroundFloodFill]).
     *
     * Anti-aliased boundary pixels are a blend of sprite color and background
     * color. Threshold-based approaches ([removeBackgroundColor], [defringeEdges])
     * either miss blends that are mostly sprite or erode genuine sprite pixels.
     * This method instead measures each pixel's *key-color excess* — how dominant
     * the chroma key's characteristic channel(s) are relative to the others — and
     * treats that excess as the fraction of background mixed into the pixel:
     *
     * 1. A depth map is built via BFS from all fully transparent pixels; only
     *    pixels within [maxDepth] of transparency are considered (sprite interior
     *    colors are never touched, even if key-like).
     * 2. For each such pixel, keyness k = dominant-channel excess (for a green
     *    key: `g - max(r, b)`; magenta: `min(r, b) - g`; blue: `b - max(r, g)`),
     *    derived automatically from [bgColor]'s channel profile.
     * 3. Contamination `t = k / keyness(bgColor)` (clamped to 0..1) is removed:
     *    alpha is scaled by `1 - t` and the color is un-mixed by subtracting the
     *    background contribution (`c' = (c - bg*t) / (1 - t)`).
     *
     * Pixels with no key-color excess (k ≤ 0) pass through unchanged, so dark
     * outlines and neutral colors at the sprite boundary are preserved.
     *
     * The original image is not modified.
     *
     * @param image the source image (typically output of [removeBackgroundFloodFill])
     * @param bgColor the chroma key color that was removed
     * @param maxDepth how many pixels in from transparency to examine (default 4;
     *     use larger values for high-resolution images with wide anti-aliasing)
     * @return a new [BufferedImage] with key contamination removed from edges
     */
    fun decontaminateEdges(
        image: BufferedImage,
        bgColor: Color,
        maxDepth: Int = 4
    ): BufferedImage {
        val w = image.width
        val h = image.height
        val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                copy.setRGB(x, y, image.getRGB(x, y))
            }
        }

        val keyness = keynessOf(bgColor) ?: return copy // gray-ish key: no dominance axis
        val bgKeyness = keyness(bgColor.red, bgColor.green, bgColor.blue)
        if (bgKeyness <= 0) return copy

        // BFS depth map from all fully transparent pixels
        val depth = IntArray(w * h) { Int.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (((image.getRGB(x, y) ushr 24) and 0xFF) == 0) {
                    depth[y * w + x] = 0
                    queue.addLast(y * w + x)
                }
            }
        }
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val d = depth[idx]
            if (d >= maxDepth) continue
            val x = idx % w
            val y = idx / w
            for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val nIdx = ny * w + nx
                if (depth[nIdx] > d + 1) {
                    depth[nIdx] = d + 1
                    queue.addLast(nIdx)
                }
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (depth[idx] == 0 || depth[idx] > maxDepth) continue
                val rgb = image.getRGB(x, y)
                val a = (rgb ushr 24) and 0xFF
                if (a == 0) continue
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                val k = keyness(r, g, b)
                if (k <= 0) continue

                val t = (k.toDouble() / bgKeyness).coerceIn(0.0, 1.0)
                val newAlpha = (a * (1.0 - t)).toInt()
                if (newAlpha < 8) {
                    copy.setRGB(x, y, 0x00000000)
                    continue
                }
                // Un-mix the background contribution from the color
                val inv = 1.0 - t
                val nr = ((r - bgColor.red * t) / inv).toInt().coerceIn(0, 255)
                val ng = ((g - bgColor.green * t) / inv).toInt().coerceIn(0, 255)
                val nb = ((b - bgColor.blue * t) / inv).toInt().coerceIn(0, 255)
                copy.setRGB(x, y, (newAlpha shl 24) or (nr shl 16) or (ng shl 8) or nb)
            }
        }

        return copy
    }

    /**
     * Derives a "keyness" function from a chroma key color's channel profile:
     * channels >= 128 are "high" (characteristic of the key), the rest "low",
     * and keyness(p) = min(high channels) - max(low channels).
     *
     * A pixel with positive keyness is dominated by the key hue (background or
     * background-contaminated); sprite colors that don't share the key's channel
     * signature — including dark outlines and neutral grays — score <= 0 and are
     * structurally immune, no matter how far the rendered background drifted
     * from the exact key hex.
     *
     * @return the keyness function, or null for a gray-ish key with no
     *     dominance axis (all channels on the same side of 128)
     */
    private fun keynessOf(bgColor: Color): ((Int, Int, Int) -> Int)? {
        val bgChannels = intArrayOf(bgColor.red, bgColor.green, bgColor.blue)
        val high = (0..2).filter { bgChannels[it] >= 128 }
        val low = (0..2).filter { bgChannels[it] < 128 }
        if (high.isEmpty() || low.isEmpty()) return null
        return { r, g, b ->
            val c = intArrayOf(r, g, b)
            high.minOf { c[it] } - low.maxOf { c[it] }
        }
    }

    /**
     * Removes the chroma key background using flood-fill from the image borders,
     * where "background" is decided by *key-channel dominance* rather than color
     * distance.
     *
     * [removeBackgroundFloodFill] compares each pixel to an exact key color
     * within a tolerance — which fails when the image model renders an off-key
     * background (too far from the requested hex to match), and can't be fixed
     * by raising the tolerance without swallowing dark sprite outlines that sit
     * close to blend colors. This variant instead asks whether the key's
     * characteristic channel(s) dominate the pixel (see [keynessOf]): a drifted
     * magenta like #92159d is still magenta-dominant, while a navy outline never
     * is.
     *
     * Only border-connected pixels with keyness >= [keyThreshold] are removed
     * (already-transparent pixels provide connectivity), so key-hued colors
     * inside the sprite survive.
     *
     * The original image is not modified.
     *
     * @param image the source image
     * @param bgColor the chroma key color (defines the dominance axis)
     * @param keyThreshold minimum keyness for a pixel to count as background
     *     (default 10 — small positive margin so neutral colors are safe)
     * @return a new [BufferedImage] with the key-dominant background removed
     */
    fun removeBackgroundKeyness(
        image: BufferedImage,
        bgColor: Color,
        keyThreshold: Int = 10
    ): BufferedImage {
        val w = image.width
        val h = image.height
        val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                copy.setRGB(x, y, image.getRGB(x, y))
            }
        }

        val keyness = keynessOf(bgColor) ?: return copy
        val visited = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()

        fun matchesBg(x: Int, y: Int): Boolean {
            val rgb = image.getRGB(x, y)
            val a = (rgb ushr 24) and 0xFF
            if (a == 0) return true // Already transparent: connectivity only
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            return keyness(r, g, b) >= keyThreshold
        }

        fun tryEnqueue(x: Int, y: Int) {
            val idx = y * w + x
            if (!visited[idx] && matchesBg(x, y)) {
                visited[idx] = true
                queue.addLast(idx)
            }
        }

        for (x in 0 until w) {
            tryEnqueue(x, 0)
            tryEnqueue(x, h - 1)
        }
        for (y in 1 until h - 1) {
            tryEnqueue(0, y)
            tryEnqueue(w - 1, y)
        }

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % w
            val y = idx / w
            copy.setRGB(x, y, 0x00000000)
            if (x > 0) tryEnqueue(x - 1, y)
            if (x < w - 1) tryEnqueue(x + 1, y)
            if (y > 0) tryEnqueue(x, y - 1)
            if (y < h - 1) tryEnqueue(x, y + 1)
        }

        return copy
    }

    /**
     * Estimates the actual background color of a chroma-keyed image by sampling
     * the border ring and averaging pixels that resemble [expected].
     *
     * Image models don't always render the requested key hex exactly — an
     * off-key background (e.g. #c32acc instead of #ff00ff) can exceed the
     * flood-fill tolerance and survive removal entirely. Feeding the *measured*
     * border color into [removeBackgroundFloodFill] makes removal robust to
     * that drift.
     *
     * Only strongly key-dominant border pixels (keyness >= 30, see [keynessOf])
     * are sampled, so dark sprite/background blend pixels can't drag the
     * estimate toward the sprite's own palette.
     *
     * @param image the source image
     * @param expected the requested chroma key color (defines the dominance axis)
     * @param ringWidth how many border pixel rows/columns to sample (default 2)
     * @return the average border background color, or null if too few opaque
     *     border pixels resemble the key (background already removed or absent)
     */
    fun estimateBorderColor(
        image: BufferedImage,
        expected: Color,
        ringWidth: Int = 2
    ): Color? {
        val keyness = keynessOf(expected) ?: return null
        val w = image.width
        val h = image.height
        var count = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L

        fun sample(x: Int, y: Int) {
            val rgb = image.getRGB(x, y)
            val a = (rgb ushr 24) and 0xFF
            if (a < 128) return
            val r = (rgb ushr 16) and 0xFF
            val g = (rgb ushr 8) and 0xFF
            val b = rgb and 0xFF
            if (keyness(r, g, b) >= 30) {
                count++
                sumR += r
                sumG += g
                sumB += b
            }
        }

        for (d in 0 until ringWidth.coerceAtMost(minOf(w, h) / 2)) {
            for (x in 0 until w) {
                sample(x, d)
                sample(x, h - 1 - d)
            }
            for (y in 1 until h - 1) {
                sample(d, y)
                sample(w - 1 - d, y)
            }
        }

        if (count < 32) return null
        return Color((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }

    /**
     * Removes small disconnected islands of non-transparent pixels — orphaned
     * background fragments, blend-noise specks, and stray dots left behind by
     * imperfect chroma key removal.
     *
     * Connected components (4-connectivity over pixels with alpha > 0) whose
     * pixel count is below [minArea] are made fully transparent. The sprite
     * itself and any intentionally separate large elements are far above any
     * sensible threshold.
     *
     * The original image is not modified.
     *
     * @param image the source image
     * @param minArea components smaller than this many pixels are removed
     * @return a new [BufferedImage] with small islands removed
     */
    fun removeSmallIslands(image: BufferedImage, minArea: Int): BufferedImage {
        val w = image.width
        val h = image.height
        val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                copy.setRGB(x, y, image.getRGB(x, y))
            }
        }

        val labeled = BooleanArray(w * h)
        val component = ArrayDeque<Int>()
        val queue = ArrayDeque<Int>()

        fun isOpaque(idx: Int): Boolean =
            ((image.getRGB(idx % w, idx / w) ushr 24) and 0xFF) > 0

        for (start in 0 until w * h) {
            if (labeled[start] || !isOpaque(start)) continue
            component.clear()
            queue.clear()
            labeled[start] = true
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                component.addLast(idx)
                val x = idx % w
                val y = idx / w
                for (nIdx in intArrayOf(
                    if (x > 0) idx - 1 else -1,
                    if (x < w - 1) idx + 1 else -1,
                    if (y > 0) idx - w else -1,
                    if (y < h - 1) idx + w else -1
                )) {
                    if (nIdx >= 0 && !labeled[nIdx] && isOpaque(nIdx)) {
                        labeled[nIdx] = true
                        queue.addLast(nIdx)
                    }
                }
            }
            if (component.size < minArea) {
                for (idx in component) {
                    copy.setRGB(idx % w, idx / w, 0x00000000)
                }
            }
        }

        return copy
    }

    /**
     * Trims transparent borders from a sprite image by cropping to the bounding
     * box of all non-transparent pixels (alpha >= [alphaThreshold]).
     *
     * If the image is fully transparent, returns a 1×1 transparent image.
     * If no transparent borders exist, returns a copy of the original with
     * [TrimResult.wasTrimmed] set to false.
     *
     * The original image is not modified.
     *
     * @param image the source image to trim
     * @param alphaThreshold minimum alpha (1–255, default 1) for a pixel to count
     *     as content; higher values ignore near-invisible halo pixels so they
     *     don't inflate the bounding box
     * @return a [TrimResult] with the trimmed image and metadata
     */
    fun trimTransparent(image: BufferedImage, alphaThreshold: Int = 1): TrimResult {
        val w = image.width
        val h = image.height

        // Find bounding box of opaque pixels
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1

        for (y in 0 until h) {
            for (x in 0 until w) {
                val alpha = (image.getRGB(x, y) ushr 24) and 0xFF
                if (alpha >= alphaThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        // Fully transparent → return 1×1 transparent image
        if (maxX < 0) {
            val tiny = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            tiny.setRGB(0, 0, 0x00000000)
            return TrimResult(
                image = tiny,
                originalWidth = w, originalHeight = h,
                trimmedWidth = 1, trimmedHeight = 1,
                wasTrimmed = true
            )
        }

        val cropW = maxX - minX + 1
        val cropH = maxY - minY + 1

        // No transparent borders → return unchanged copy
        if (minX == 0 && minY == 0 && cropW == w && cropH == h) {
            val copy = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = copy.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            return TrimResult(
                image = copy,
                originalWidth = w, originalHeight = h,
                trimmedWidth = w, trimmedHeight = h,
                wasTrimmed = false
            )
        }

        // Crop to bounding box
        val cropped = BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_ARGB)
        val g = cropped.createGraphics()
        g.drawImage(image, 0, 0, cropW, cropH, minX, minY, maxX + 1, maxY + 1, null)
        g.dispose()

        return TrimResult(
            image = cropped,
            originalWidth = w, originalHeight = h,
            trimmedWidth = cropW, trimmedHeight = cropH,
            wasTrimmed = true
        )
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
