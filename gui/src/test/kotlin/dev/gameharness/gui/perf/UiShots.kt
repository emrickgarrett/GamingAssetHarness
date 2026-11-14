package dev.gameharness.gui.perf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import dev.gameharness.core.model.Workspace
import dev.gameharness.gui.components.AssetPreview
import dev.gameharness.gui.theme.GameHarnessTheme
import dev.gameharness.gui.thumbs.Thumbnails
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Drives the asset panel of a real workspace with no window on screen and
 * saves pictures of it: open a folder, open a model, orbit it, pause its
 * animation. This is how a change to the browser or the 3D viewer is looked at
 * without taking over the desktop.
 *
 * The panel is the application's own (`AssetPreview`: the grid, the detail
 * view, the model viewer), composed into an [ImageComposeScene]. The workspace
 * file is only read, and everything that would change it does nothing.
 *
 *   ./gradlew :gui:uiShots --args="<workspace.json> <out-dir> <width> <height> <action> ..."
 *
 * Actions, run in order:
 *   folder:<path>              show that folder
 *   wait:<ms>                  keep drawing frames for that long
 *   settle                     draw until no thumbnail is being made (and at least 300 ms)
 *   shot:<name>                save <out-dir>/<name>.png
 *   click:<x>,<y>              left click
 *   drag:<x1>,<y1>,<x2>,<y2>   left drag; append ,right for the right button, ,shift to hold Shift
 *   scroll:<x>,<y>,<notches>   wheel at a point, positive down
 */
@OptIn(ExperimentalComposeUiApi::class)
object UiShots {

    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(rawArgs: Array<String>) {
        val cache = rawArgs.firstOrNull { it.startsWith("--cache=") }?.substringAfter("=")
        System.setProperty("gameharness.thumbnailCache", cache ?: "off")
        val args = rawArgs.filterNot { it.startsWith("--") }
        if (args.size < 5) {
            System.err.println("usage: <workspace.json> <out-dir> <width> <height> <action> ... [--cache=<dir>]")
            kotlin.system.exitProcess(2)
        }
        val workspace = json.decodeFromString(Workspace.serializer(), File(args[0]).readText())
        val outDir = File(args[1]).apply { mkdirs() }
        val width = args[2].toInt()
        val height = args[3].toInt()

        var currentFolder by mutableStateOf("")
        val scene = ImageComposeScene(width, height, Density(1f)) {
            GameHarnessTheme(darkTheme = true) {
                AssetPreview(
                    preview = null,
                    assets = workspace.assets,
                    folders = workspace.folders,
                    currentFolder = currentFolder,
                    onNavigateToFolder = { currentFolder = it },
                    onNavigateUp = { currentFolder = currentFolder.substringBeforeLast("/", "") },
                )
            }
        }

        fun frame() = scene.render(System.nanoTime())
        fun run(ms: Long) {
            val end = System.nanoTime() + ms * 1_000_000
            do {
                frame()
                Thread.sleep(10)
            } while (System.nanoTime() < end)
        }

        val none = PointerButtons()
        for (action in args.drop(4)) {
            val name = action.substringBefore(":")
            val arg = action.substringAfter(":", "")
            val started = System.nanoTime()
            when (name) {
                "folder" -> { currentFolder = arg; frame() }
                "wait" -> run(arg.toLong())
                "settle" -> {
                    run(300)
                    while (Thumbnails.store.pending > 0) run(50)
                    run(100)
                }
                "shot" -> {
                    val data = frame().encodeToData(EncodedImageFormat.PNG)!!
                    File(outDir, "$arg.png").writeBytes(data.bytes)
                }
                "click" -> {
                    val (x, y) = arg.split(",").map { it.toFloat() }
                    val at = Offset(x, y)
                    val down = PointerButtons(isPrimaryPressed = true)
                    scene.sendPointerEvent(PointerEventType.Move, at, buttons = none)
                    frame()
                    scene.sendPointerEvent(PointerEventType.Press, at, buttons = down, button = PointerButton.Primary)
                    frame()
                    scene.sendPointerEvent(PointerEventType.Release, at, buttons = none, button = PointerButton.Primary)
                    run(60)
                }
                "drag" -> {
                    val parts = arg.split(",")
                    val from = Offset(parts[0].toFloat(), parts[1].toFloat())
                    val to = Offset(parts[2].toFloat(), parts[3].toFloat())
                    val right = "right" in parts
                    val button = if (right) PointerButton.Secondary else PointerButton.Primary
                    val down = PointerButtons(isPrimaryPressed = !right, isSecondaryPressed = right)
                    val modifiers = PointerKeyboardModifiers(isShiftPressed = "shift" in parts)
                    scene.sendPointerEvent(PointerEventType.Move, from, buttons = none)
                    frame()
                    scene.sendPointerEvent(PointerEventType.Press, from, buttons = down, keyboardModifiers = modifiers, button = button)
                    val steps = 12
                    for (i in 1..steps) {
                        val at = from + (to - from) * (i / steps.toFloat())
                        scene.sendPointerEvent(PointerEventType.Move, at, buttons = down, keyboardModifiers = modifiers)
                        frame()
                        Thread.sleep(16)
                    }
                    scene.sendPointerEvent(PointerEventType.Release, to, buttons = none, button = button)
                    run(120)
                }
                "scroll" -> {
                    val (x, y, notches) = arg.split(",").map { it.toFloat() }
                    val steps = kotlin.math.abs(notches).toInt().coerceAtLeast(1)
                    repeat(steps) {
                        scene.sendPointerEvent(
                            PointerEventType.Scroll, Offset(x, y),
                            scrollDelta = Offset(0f, if (notches < 0) -1f else 1f), buttons = none,
                        )
                        frame()
                        Thread.sleep(16)
                    }
                    run(120)
                }
                else -> {
                    System.err.println("unknown action: $action")
                    kotlin.system.exitProcess(2)
                }
            }
            println("%-34s %7.0f ms".format(action, (System.nanoTime() - started) / 1e6))
        }
        scene.close()
        kotlin.system.exitProcess(0)
    }
}
