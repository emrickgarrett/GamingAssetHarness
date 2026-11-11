package dev.gameharness.cli.commands

import dev.gameharness.api.img2threejs.SculptRunner
import dev.gameharness.cli.commands.sculpt.findRepoDir
import dev.gameharness.cli.commands.sculpt.resolveForgeRoot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Path resolution for the sculpt commands.
 *
 * Worth testing specifically: every bug found while building these commands was
 * a path-resolution bug. The CLI's working directory is NOT the repo root —
 * Gradle puts it in `cli/`, an installed dist puts it wherever the user is — so
 * bare relative defaults silently miss, and forge scripts run with the checkout
 * as their working directory, which resolves the caller's relative paths
 * against the wrong root.
 */
class SculptPathResolutionTest {

    private fun fakeCheckout(root: Path, relative: String = "third_party/img2threejs"): File {
        val checkout = root.resolve(relative).toFile()
        File(checkout, "forge").mkdirs()
        return checkout
    }

    @Test
    fun `explicit forge path is used when it holds a forge directory`(@TempDir tmp: Path) {
        val checkout = fakeCheckout(tmp, "somewhere/img2threejs")
        assertEquals(checkout.absoluteFile, resolveForgeRoot(checkout.path))
    }

    @Test
    fun `explicit forge path without a forge directory is rejected loudly`(@TempDir tmp: Path) {
        val notACheckout = tmp.resolve("empty").toFile().apply { mkdirs() }
        // silently falling through to a search would hide the user's typo
        val e = assertFailsWith<SculptRunner.SculptException> { resolveForgeRoot(notACheckout.path) }
        assertTrue(e.message!!.contains("no forge/ directory"), "unexpected message: ${e.message}")
    }

    @Test
    fun `findRepoDir walks up from a nested directory`(@TempDir tmp: Path) {
        val checkout = fakeCheckout(tmp)
        // stand where the CLI actually runs: several levels below the repo root
        val nested = tmp.resolve("cli/build/classes/kotlin/main").toFile().apply { mkdirs() }

        val found = generateSequence(nested) { it.parentFile }
            .map { File(it, "third_party/img2threejs") }
            .firstOrNull { File(it, "forge").isDirectory }

        assertNotNull(found, "should find the checkout above the nested start")
        assertEquals(checkout.canonicalFile, found.canonicalFile)
    }

    @Test
    fun `findRepoDir returns null when nothing matches`() {
        assertNull(findRepoDir("definitely/not/a/real/harness/dir"))
    }

    @Test
    fun `SculptRunner rejects a directory that is not a checkout`(@TempDir tmp: Path) {
        val e = assertFailsWith<SculptRunner.SculptException> {
            SculptRunner(tmp.toFile())
        }
        assertTrue(e.message!!.contains("no forge/"), "unexpected message: ${e.message}")
    }

    @Test
    fun `SculptRunner rejects a missing directory`(@TempDir tmp: Path) {
        val missing = tmp.resolve("does-not-exist").toFile()
        val e = assertFailsWith<SculptRunner.SculptException> { SculptRunner(missing) }
        assertTrue(e.message!!.contains("not found"), "unexpected message: ${e.message}")
    }

    @Test
    fun `default interpreter matches the platform`() {
        val expected = if (System.getProperty("os.name").startsWith("Windows", true)) "python" else "python3"
        assertEquals(expected, SculptRunner.defaultPython())
    }
}
