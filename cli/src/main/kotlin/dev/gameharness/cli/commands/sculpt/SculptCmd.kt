package dev.gameharness.cli.commands.sculpt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import dev.gameharness.api.img2threejs.SculptRunner
import java.io.File

/**
 * `harness sculpt ...` — the img2threejs procedural-model pipeline.
 *
 * Deliberately NOT under `generate`: every other provider is one call that
 * returns a finished asset, whereas this is an eight-pass loop
 * (blockout → structural → form → material → surface → lighting → interaction
 * → optimization) where Python scripts enforce the gates but a vision-capable
 * agent authors the spec and scores each reference-vs-render comparison sheet.
 * The subcommands here are the agent's hands; `sculpt next` tells it what to do.
 *
 * Pipeline state lives in the spec JSON, not in the harness, so these commands
 * stay stateless and the same spec can be driven from the CLI or the GUI.
 */
class SculptCmd : CliktCommand(
    name = "sculpt",
    help = "Build procedural Three.js models from a reference image (img2threejs)"
) {
    override fun run() = Unit
}

/**
 * Resolves the img2threejs checkout. Precedence: explicit flag, then
 * IMG2THREEJS_PATH, then the vendored third_party/ checkout relative to the
 * working directory. Kept out of AppConfig on purpose for now — this is a
 * local tool path, not an API credential, and threading it through the
 * settings schema would touch the GUI too.
 */
internal fun resolveForgeRoot(explicit: String?): File {
    fun ok(f: File?) = f != null && File(f, "forge").isDirectory

    explicit?.let {
        val f = File(it).absoluteFile
        if (ok(f)) return f
        throw SculptRunner.SculptException("--forge path has no forge/ directory: ${f.path}")
    }
    System.getenv("IMG2THREEJS_PATH")?.takeIf { it.isNotBlank() }?.let {
        val f = File(it).absoluteFile
        if (ok(f)) return f
        throw SculptRunner.SculptException("IMG2THREEJS_PATH has no forge/ directory: ${f.path}")
    }

    findRepoDir("third_party/img2threejs") { ok(it) }?.let { return it }
    throw SculptRunner.SculptException(
        "img2threejs checkout not found (looked for third_party/img2threejs above " +
            "${searchRoots().joinToString(" and ") { it.path }}). Clone " +
            "https://github.com/img2threejs/img2threejs there, pass --forge, or set IMG2THREEJS_PATH."
    )
}

/**
 * Where to start walking up from. The CLI is rarely launched from the repo
 * root — a gradle wrapper puts the working directory in `cli/`, an installed
 * dist puts it wherever the user happens to be — so a bare relative path
 * silently misses. Search from the working directory AND from the location of
 * the running code.
 */
internal fun searchRoots(): List<File> = listOfNotNull(
    File("").absoluteFile,
    runCatching {
        File(SculptRunner::class.java.protectionDomain.codeSource.location.toURI()).parentFile
    }.getOrNull(),
)

/** Finds a repo-relative directory by walking up from [searchRoots]. */
internal fun findRepoDir(relative: String, accept: (File) -> Boolean = { it.isDirectory }): File? {
    for (start in searchRoots()) {
        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, relative)
            if (accept(candidate)) return candidate.absoluteFile
            dir = dir.parentFile
        }
    }
    return null
}

/** Shared `--forge` option so every subcommand resolves the checkout the same way. */
internal fun CliktCommand.forgeOption() =
    option("--forge", help = "Path to the img2threejs checkout (default: \$IMG2THREEJS_PATH or third_party/img2threejs)")
