package dev.gameharness.core.workspace

import dev.gameharness.core.util.ensureDirectoryExists
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Serializable data holder for the list of known workspace directory paths.
 *
 * @property workspacePaths Absolute paths to workspace root directories.
 */
@Serializable
data class WorkspaceRegistryData(
    val workspacePaths: List<String> = emptyList()
)

/**
 * Persists a list of workspace directory paths so the app can discover
 * workspaces scattered across the filesystem (IDE-style).
 *
 * Registry file: `{registryDir}/workspace-registry.json`
 */
class WorkspaceRegistry(private val registryDir: Path) {

    private val log = LoggerFactory.getLogger(WorkspaceRegistry::class.java)
    private val registryFile: Path = registryDir.resolve("workspace-registry.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Loads the registry from disk, returning an empty registry if the file is missing or corrupt. */
    fun load(): WorkspaceRegistryData {
        if (!Files.exists(registryFile)) return WorkspaceRegistryData()
        return try {
            val content = Files.readString(registryFile)
            json.decodeFromString<WorkspaceRegistryData>(content)
        } catch (e: Exception) {
            log.warn("Failed to load workspace registry: {}", e.message)
            WorkspaceRegistryData()
        }
    }

    /** Persists the registry to disk atomically (write to temp file, then rename). */
    fun save(data: WorkspaceRegistryData) {
        registryDir.ensureDirectoryExists()
        val content = json.encodeToString(data)
        val tempFile = registryDir.resolve("workspace-registry.json.tmp")
        Files.writeString(tempFile, content)
        Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Adds a workspace path to the registry if it is not already present. */
    fun addPath(path: String) {
        val data = load()
        if (path !in data.workspacePaths) {
            save(data.copy(workspacePaths = data.workspacePaths + path))
        }
    }

    /** Removes a workspace path from the registry. */
    fun removePath(path: String) {
        val data = load()
        save(data.copy(workspacePaths = data.workspacePaths.filter { it != path }))
    }

    /** Returns all registered workspace paths. */
    fun listPaths(): List<String> = load().workspacePaths
}
