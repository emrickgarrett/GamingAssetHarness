package dev.gameharness.api.img2threejs

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin process wrapper around the img2threejs "forge" pipeline
 * (https://github.com/img2threejs/img2threejs, Apache-2.0).
 *
 * WHY THIS ISN'T AN [dev.gameharness.api.common.AssetGenerationClient]:
 * every other provider is "POST a prompt, poll, download a file". img2threejs
 * is an agent-driven pipeline — eight gated passes where Python scripts
 * enforce validation and state but a vision-capable model authors the spec and
 * JUDGES each reference-vs-render comparison sheet. There is no single call
 * that returns an asset, so pretending it satisfies `generate()` would be a
 * lie. It gets its own command surface (`harness sculpt ...`) instead, and the
 * agent drives the loop.
 *
 * The forge scripts have NO third-party Python dependencies (stdlib only,
 * Python >= 3.10), so this needs nothing but an interpreter on PATH.
 */
class SculptRunner(
    /** Checkout of img2threejs; contains the `forge/` directory. */
    private val forgeRoot: File,
    private val python: String = defaultPython(),
) {
    class SculptException(message: String) : RuntimeException(message)

    data class Output(val exitCode: Int, val stdout: String, val stderr: String) {
        val ok: Boolean get() = exitCode == 0
    }

    init {
        if (!forgeRoot.isDirectory) {
            throw SculptException(
                "img2threejs not found at ${forgeRoot.absolutePath}. " +
                    "Clone it there, or set img2threejsPath in settings."
            )
        }
        if (!File(forgeRoot, "forge").isDirectory) {
            throw SculptException("${forgeRoot.absolutePath} has no forge/ directory — is this an img2threejs checkout?")
        }
    }

    /**
     * Runs one forge script. [relativeScript] is relative to the checkout root,
     * e.g. `forge/stage1_intake/probe_image.py`.
     *
     * Runs with the CHECKOUT as the working directory: the scripts resolve
     * their sibling modules (`_shared`, `stage3_build`) relative to their own
     * location, and several print commands that assume `forge/...` paths.
     */
    fun script(relativeScript: String, vararg args: String): Output {
        val script = File(forgeRoot, relativeScript)
        if (!script.isFile) throw SculptException("no such forge script: $relativeScript")
        return exec(listOf(python, script.absolutePath) + args)
    }

    /**
     * `forge/next.py` — the pipeline driver. Reports the current pass, the
     * exact next command, and the acceptance criteria still unmet. This is the
     * single best entry point for an agent driving the loop.
     */
    fun next(spec: File): Output = script("forge/next.py", spec.absolutePath)

    /** Verifies the interpreter is present and new enough (the scripts need >= 3.10). */
    fun checkPython(): String {
        val out = exec(listOf(python, "--version"))
        if (!out.ok) throw SculptException("'$python' is not runnable — install Python 3.10+ or set the interpreter")
        val version = (out.stdout + out.stderr).trim()
        val m = Regex("""(\d+)\.(\d+)""").find(version)
            ?: throw SculptException("could not parse Python version from '$version'")
        val (major, minor) = m.destructured.toList().map(String::toInt)
        if (major < 3 || (major == 3 && minor < 10)) {
            throw SculptException("img2threejs needs Python >= 3.10, found $version")
        }
        return version
    }

    private fun exec(command: List<String>): Output {
        val proc = ProcessBuilder(command)
            .directory(forgeRoot)
            .redirectErrorStream(false)
            .start()
        // read both pipes before waiting: a full stderr buffer deadlocks the child
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        if (!proc.waitFor(PROCESS_TIMEOUT_MIN, TimeUnit.MINUTES)) {
            proc.destroyForcibly()
            throw SculptException("forge script timed out after $PROCESS_TIMEOUT_MIN min: ${command.joinToString(" ")}")
        }
        return Output(proc.exitValue(), stdout, stderr)
    }

    companion object {
        private const val PROCESS_TIMEOUT_MIN = 10L

        /** Windows ships `python`; most other platforms need `python3`. */
        fun defaultPython(): String =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
    }
}
