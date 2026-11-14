package dev.gameharness.gui.perf

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.gameharness.gui.App
import dev.gameharness.gui.LocalAwtWindow
import java.awt.Component
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Runs the whole application in a real window and works it with made-up mouse
 * events, saving pictures of the window as it goes.
 *
 * [UiShots] covers the asset panel with no window at all; this covers what that
 * cannot: the real window, the real event thread, the GPU-backed drawing, and
 * the 3D view's own OpenGL thread running beside them.
 *
 * The events are posted straight to the window, so the real pointer is never
 * moved. The window does not take keyboard focus but does stay on top, because
 * the pictures are read back from the screen.
 *
 * It runs the real `App()`, so point it at a throwaway home directory:
 *
 *   ./gradlew :gui:windowCheck -PcheckHome=<dir> --args="<out-dir> <width> <height> <action> ..."
 *
 * with `<dir>/.gameharness/workspace-registry.json` listing the workspaces to
 * offer. Actions: wait:<ms>, shot:<name>, click:<x>,<y>,
 * drag:<x1>,<y1>,<x2>,<y2>[,right], scroll:<x>,<y>,<notches>. Coordinates are
 * in the window's content area.
 */
object WindowCheck {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 4) {
            System.err.println("usage: <out-dir> <width> <height> <action> ...")
            kotlin.system.exitProcess(2)
        }
        val outDir = File(args[0]).apply { mkdirs() }
        val width = args[1].toInt()
        val height = args[2].toInt()
        val actions = args.drop(3)

        application {
            val state = rememberWindowState(width = width.dp, height = height.dp, position = WindowPosition(0.dp, 0.dp))
            Window(
                onCloseRequest = ::exitApplication,
                title = "Game Developer Harness (window check)",
                state = state,
                focusable = false,
                alwaysOnTop = true,
            ) {
                CompositionLocalProvider(LocalAwtWindow provides window) {
                    App()
                }
                LaunchedEffect(Unit) {
                    thread(name = "window-check-script", isDaemon = true) {
                        try {
                            runScript(window, outDir, actions)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        } finally {
                            kotlin.system.exitProcess(0)
                        }
                    }
                }
            }
        }
    }

    private fun runScript(window: java.awt.Window, outDir: File, actions: List<String>) {
        val content = (window as javax.swing.RootPaneContainer).contentPane
        val robot = Robot()
        Thread.sleep(1500) // first frames, fonts, the agent starting

        fun post(id: Int, x: Int, y: Int, modifiers: Int, button: Int, clicks: Int = 0) {
            EventQueue.invokeAndWait {
                val target: Component = SwingUtilities.getDeepestComponentAt(content, x, y) ?: content
                val p = SwingUtilities.convertPoint(content, x, y, target)
                target.dispatchEvent(
                    MouseEvent(target, id, System.currentTimeMillis(), modifiers, p.x, p.y, clicks, false, button)
                )
            }
        }

        for (action in actions) {
            val name = action.substringBefore(":")
            val arg = action.substringAfter(":", "")
            when (name) {
                "wait" -> Thread.sleep(arg.toLong())
                "shot" -> {
                    val origin = content.locationOnScreen
                    val picture = robot.createScreenCapture(Rectangle(origin.x, origin.y, content.width, content.height))
                    ImageIO.write(picture, "png", File(outDir, "$arg.png"))
                }
                "click" -> {
                    val (x, y) = arg.split(",").map { it.toInt() }
                    post(MouseEvent.MOUSE_MOVED, x, y, 0, MouseEvent.NOBUTTON)
                    Thread.sleep(40)
                    post(MouseEvent.MOUSE_PRESSED, x, y, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1, 1)
                    Thread.sleep(40)
                    post(MouseEvent.MOUSE_RELEASED, x, y, 0, MouseEvent.BUTTON1, 1)
                    Thread.sleep(120)
                }
                "drag" -> {
                    val parts = arg.split(",")
                    val x1 = parts[0].toInt(); val y1 = parts[1].toInt()
                    val x2 = parts[2].toInt(); val y2 = parts[3].toInt()
                    val right = "right" in parts
                    val mask = if (right) InputEvent.BUTTON3_DOWN_MASK else InputEvent.BUTTON1_DOWN_MASK
                    val button = if (right) MouseEvent.BUTTON3 else MouseEvent.BUTTON1
                    post(MouseEvent.MOUSE_MOVED, x1, y1, 0, MouseEvent.NOBUTTON)
                    Thread.sleep(40)
                    post(MouseEvent.MOUSE_PRESSED, x1, y1, mask, button, 1)
                    val steps = 20
                    for (i in 1..steps) {
                        Thread.sleep(16)
                        post(MouseEvent.MOUSE_DRAGGED, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps, mask, MouseEvent.NOBUTTON)
                    }
                    Thread.sleep(40)
                    post(MouseEvent.MOUSE_RELEASED, x2, y2, 0, button, 1)
                    Thread.sleep(150)
                }
                "scroll" -> {
                    val (x, y, notches) = arg.split(",").map { it.toInt() }
                    repeat(kotlin.math.abs(notches)) {
                        EventQueue.invokeAndWait {
                            val target: Component = SwingUtilities.getDeepestComponentAt(content, x, y) ?: content
                            val p = SwingUtilities.convertPoint(content, x, y, target)
                            target.dispatchEvent(
                                MouseWheelEvent(
                                    target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, p.x, p.y, 0, false,
                                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, if (notches < 0) -1 else 1,
                                )
                            )
                        }
                        Thread.sleep(30)
                    }
                    Thread.sleep(150)
                }
                else -> System.err.println("unknown action: $action")
            }
            println("done $action")
        }
    }
}
