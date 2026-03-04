package dev.gameharness.core.workspace

import dev.gameharness.core.model.*
import dev.gameharness.core.util.ensureDirectoryExists
import dev.gameharness.core.util.readBytesSafely
import dev.gameharness.core.util.writeBytesSafely
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Manages workspaces that can live anywhere on the filesystem.
 * Workspace locations are tracked via a [WorkspaceRegistry] stored in [registryDir].
 *
 * All mutating operations return a new [Workspace] instance with the changes applied.
 * The original workspace object is never modified.
 */
class WorkspaceManager(registryDir: Path) {

    private val log = LoggerFactory.getLogger(WorkspaceManager::class.java)
    private val registry = WorkspaceRegistry(registryDir)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Creates a new workspace in the user-specified [directory].
     * The directory must either not exist (will be created) or be empty.
     * Creates workspace.json and asset subdirectories inside it.
     */
    fun createWorkspace(name: String, directory: Path): Workspace {
        require(name.isNotBlank()) { "Workspace name must not be blank" }

        if (directory.exists()) {
            val isEmpty = Files.list(directory).use { it.findFirst().isEmpty }
            require(isEmpty) { "Directory is not empty: $directory" }
        } else {
            directory.ensureDirectoryExists()
        }

        AssetType.entries.forEach { type ->
            directory.resolve("assets").resolve(type.subdirectory).ensureDirectoryExists()
        }

        val workspace = Workspace(
            name = name,
            directoryPath = directory.toAbsolutePath().toString()
        )

        saveWorkspaceMetadata(workspace)
        registry.addPath(directory.toAbsolutePath().toString())
        log.info("Created workspace '{}' at {}", name, directory.toAbsolutePath())
        return workspace
    }

    /**
     * Lists all known workspaces from the registry.
     * Automatically removes stale entries (paths that no longer exist or
     * no longer contain a valid workspace.json).
     */
    fun listWorkspaces(): List<Workspace> {
        val paths = registry.listPaths()
        val workspaces = mutableListOf<Workspace>()
        val stalePaths = mutableListOf<String>()

        for (pathStr in paths) {
            val dir = Path.of(pathStr)
            if (!dir.exists() || !dir.isDirectory()) {
                stalePaths.add(pathStr)
                continue
            }
            val ws = loadWorkspaceMetadata(dir)
            if (ws != null) {
                workspaces.add(ws)
            } else {
                stalePaths.add(pathStr)
            }
        }

        if (stalePaths.isNotEmpty()) {
            stalePaths.forEach { registry.removePath(it) }
            log.info("Removed {} stale workspace registry entries", stalePaths.size)
        }

        return workspaces.sortedByDescending { it.createdAt }
    }

    /**
     * Renames a workspace's display name only.
     * Does NOT move or rename the directory — the user chose its location.
     * Returns the updated workspace.
     */
    fun renameWorkspace(workspace: Workspace, newName: String): Workspace {
        require(newName.isNotBlank()) { "Workspace name must not be blank" }
        val dir = Path.of(workspace.directoryPath)
        require(dir.exists()) { "Workspace directory does not exist" }

        val renamed = workspace.copy(name = newName)
        saveWorkspaceMetadata(renamed)
        return renamed
    }

    /**
     * Deletes all files in the workspace directory and removes it from the registry.
     */
    fun deleteWorkspace(workspace: Workspace) {
        val workspaceDir = Path.of(workspace.directoryPath)
        if (workspaceDir.exists()) {
            Files.walk(workspaceDir).use { walk ->
                walk.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
        registry.removePath(workspace.directoryPath)
    }

    /**
     * Finds a workspace by name, searching through the registry.
     */
    fun getWorkspace(name: String): Workspace? {
        return listWorkspaces().find { it.name == name }
    }

    /**
     * Imports an existing workspace directory into the registry.
     * The directory must already contain a valid workspace.json.
     */
    fun importWorkspace(directory: Path): Workspace? {
        val workspace = loadWorkspaceMetadata(directory)
        if (workspace != null) {
            registry.addPath(directory.toAbsolutePath().toString())
        }
        return workspace
    }

    // --- Asset management ---

    /**
     * Saves an asset's bytes to disk and adds it to the workspace.
     * Returns a pair of (saved file path, updated workspace).
     */
    fun saveAsset(workspace: Workspace, asset: GeneratedAsset, bytes: ByteArray): Pair<Path, Workspace> {
        val workspaceDir = Path.of(workspace.directoryPath)
        val assetDir = workspaceDir.resolve("assets").resolve(asset.type.subdirectory)
        assetDir.ensureDirectoryExists()

        val targetPath = assetDir.resolve(asset.fileName)
        targetPath.writeBytesSafely(bytes)

        val savedAsset = asset.copy(filePath = targetPath.toString(), sizeBytes = bytes.size.toLong())
        val updated = workspace.copy(assets = workspace.assets + savedAsset)
        saveWorkspaceMetadata(updated)

        return targetPath to updated
    }

    /** Reads the raw file bytes for an asset from disk. */
    fun loadAssetBytes(asset: GeneratedAsset): ByteArray {
        return Path.of(asset.filePath).readBytesSafely()
    }

    /**
     * Updates the approval status of an asset. Returns the updated workspace.
     */
    fun updateAssetStatus(workspace: Workspace, assetId: String, decision: AssetDecision): Workspace {
        val updated = workspace.copy(
            assets = workspace.assets.map { asset ->
                if (asset.id == assetId) asset.copy(status = decision) else asset
            }
        )
        saveWorkspaceMetadata(updated)
        return updated
    }

    /**
     * Deletes a single asset from disk and returns the updated workspace.
     */
    fun deleteAsset(workspace: Workspace, assetId: String): Workspace {
        val asset = workspace.assets.find { it.id == assetId } ?: return workspace
        val path = Path.of(asset.filePath)
        if (path.exists()) {
            Files.delete(path)
        }
        val updated = workspace.copy(assets = workspace.assets.filter { it.id != assetId })
        saveWorkspaceMetadata(updated)
        return updated
    }

    /**
     * Deletes multiple assets from disk and returns the updated workspace.
     */
    fun deleteAssets(workspace: Workspace, assetIds: Set<String>): Workspace {
        if (assetIds.isEmpty()) return workspace
        val toDelete = workspace.assets.filter { it.id in assetIds }
        for (asset in toDelete) {
            val path = Path.of(asset.filePath)
            if (path.exists()) {
                Files.delete(path)
            }
        }
        val updated = workspace.copy(assets = workspace.assets.filter { it.id !in assetIds })
        saveWorkspaceMetadata(updated)
        log.info("Deleted {} assets from workspace '{}'", toDelete.size, workspace.name)
        return updated
    }

    /**
     * Moves multiple assets to a target folder. Returns the updated workspace.
     */
    fun moveAssetsToFolder(workspace: Workspace, assetIds: Set<String>, targetFolder: String): Workspace {
        if (assetIds.isEmpty()) return workspace
        val normalized = if (targetFolder.isBlank()) "" else normalizeFolderPath(targetFolder)
        if (normalized.isNotEmpty()) {
            require(normalized in workspace.folders) { "Target folder does not exist: $normalized" }
        }
        var movedCount = 0
        val updatedAssets = workspace.assets.map { asset ->
            if (asset.id in assetIds) {
                movedCount++
                asset.copy(folder = normalized)
            } else {
                asset
            }
        }
        val updated = workspace.copy(assets = updatedAssets)
        saveWorkspaceMetadata(updated)
        log.info("Moved {} assets to folder '{}' in workspace '{}'", movedCount, normalized, workspace.name)
        return updated
    }

    // --- Folder management ---

    /**
     * Creates a new folder (and any missing parent folders). Returns the updated workspace.
     */
    fun createFolder(workspace: Workspace, folderPath: String): Workspace {
        require(folderPath.isNotBlank()) { "Folder path must not be blank" }
        val normalized = normalizeFolderPath(folderPath)
        require(normalized !in workspace.folders) { "Folder already exists: $normalized" }

        // Auto-create all parent folders
        val newFolders = mutableSetOf<String>()
        val parts = normalized.split("/")
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            newFolders.add(current)
        }

        val updated = workspace.copy(folders = workspace.folders + newFolders)
        saveWorkspaceMetadata(updated)
        log.info("Created folder '{}' in workspace '{}'", normalized, workspace.name)
        return updated
    }

    /**
     * Deletes a folder and moves its assets to the parent folder. Returns the updated workspace.
     */
    fun deleteFolder(workspace: Workspace, folderPath: String): Workspace {
        val normalized = normalizeFolderPath(folderPath)
        require(normalized in workspace.folders) { "Folder does not exist: $normalized" }

        val parentFolder = normalized.substringBeforeLast("/", "")

        // Move all assets in this folder or subfolders to the parent
        val updatedAssets = workspace.assets.map { asset ->
            if (asset.folder == normalized || asset.folder.startsWith("$normalized/")) {
                asset.copy(folder = parentFolder)
            } else {
                asset
            }
        }

        // Remove this folder and all subfolders from the set
        val updatedFolders = workspace.folders.filter {
            it != normalized && !it.startsWith("$normalized/")
        }.toSet()

        val updated = workspace.copy(assets = updatedAssets, folders = updatedFolders)
        saveWorkspaceMetadata(updated)
        log.info("Deleted folder '{}' from workspace '{}'", normalized, workspace.name)
        return updated
    }

    /**
     * Renames a folder and updates all asset references. Returns the updated workspace.
     */
    fun renameFolder(workspace: Workspace, oldPath: String, newName: String): Workspace {
        require(newName.isNotBlank()) { "Folder name must not be blank" }
        require(!newName.contains("/")) { "Folder name must not contain '/'" }

        val normalizedOld = normalizeFolderPath(oldPath)
        require(normalizedOld in workspace.folders) { "Folder does not exist: $normalizedOld" }

        val parentPath = normalizedOld.substringBeforeLast("/", "")
        val newPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
        require(newPath !in workspace.folders) { "A folder named '$newName' already exists at this level" }

        // Update all subfolders in the set
        val updatedFolders = workspace.folders.map { folder ->
            when {
                folder == normalizedOld -> newPath
                folder.startsWith("$normalizedOld/") -> folder.replaceFirst(normalizedOld, newPath)
                else -> folder
            }
        }.toSet()

        // Update all asset folder references
        val updatedAssets = workspace.assets.map { asset ->
            when {
                asset.folder == normalizedOld -> asset.copy(folder = newPath)
                asset.folder.startsWith("$normalizedOld/") ->
                    asset.copy(folder = asset.folder.replaceFirst(normalizedOld, newPath))
                else -> asset
            }
        }

        val updated = workspace.copy(assets = updatedAssets, folders = updatedFolders)
        saveWorkspaceMetadata(updated)
        log.info("Renamed folder '{}' to '{}' in workspace '{}'", normalizedOld, newPath, workspace.name)
        return updated
    }

    /**
     * Moves a single asset to a target folder. Returns the updated workspace.
     */
    fun moveAssetToFolder(workspace: Workspace, assetId: String, targetFolder: String): Workspace {
        val normalized = if (targetFolder.isBlank()) "" else normalizeFolderPath(targetFolder)
        if (normalized.isNotEmpty()) {
            require(normalized in workspace.folders) { "Target folder does not exist: $normalized" }
        }

        val index = workspace.assets.indexOfFirst { it.id == assetId }
        require(index >= 0) { "Asset not found: $assetId" }

        val updatedAssets = workspace.assets.mapIndexed { i, asset ->
            if (i == index) asset.copy(folder = normalized) else asset
        }
        val updated = workspace.copy(assets = updatedAssets)
        saveWorkspaceMetadata(updated)
        log.info("Moved asset '{}' to folder '{}' in workspace '{}'", assetId, normalized, workspace.name)
        return updated
    }

    private fun normalizeFolderPath(path: String): String {
        return path.trim().trim('/').replace("\\", "/")
    }

    /** Writes per-workspace AI instructions to `workspace-instructions.md`. */
    fun saveWorkspaceContext(workspace: Workspace, context: String) {
        val contextPath = Path.of(workspace.directoryPath).resolve("workspace-instructions.md")
        Files.writeString(contextPath, context)
    }

    /** Reads per-workspace AI instructions, or returns an empty string if none exist. */
    fun loadWorkspaceContext(workspace: Workspace): String {
        val contextPath = Path.of(workspace.directoryPath).resolve("workspace-instructions.md")
        if (!contextPath.exists()) return ""
        return Files.readString(contextPath)
    }

    /** Persists the conversation history to `chat-history.json` in the workspace directory. */
    fun saveChatHistory(workspace: Workspace, messages: List<ChatMessage>) {
        val workspaceDir = Path.of(workspace.directoryPath)
        val historyPath = workspaceDir.resolve("chat-history.json")
        val content = json.encodeToString(messages)
        Files.writeString(historyPath, content)
    }

    /** Loads the conversation history from disk, or returns an empty list if none exists. */
    fun loadChatHistory(workspace: Workspace): List<ChatMessage> {
        val workspaceDir = Path.of(workspace.directoryPath)
        val historyPath = workspaceDir.resolve("chat-history.json")
        if (!historyPath.exists()) return emptyList()
        val content = Files.readString(historyPath)
        return json.decodeFromString(content)
    }

    private fun saveWorkspaceMetadata(workspace: Workspace) {
        val workspaceDir = Path.of(workspace.directoryPath)
        val metadataPath = workspaceDir.resolve("workspace.json")
        val content = json.encodeToString(workspace)
        Files.writeString(metadataPath, content)
    }

    private fun loadWorkspaceMetadata(workspaceDir: Path): Workspace? {
        val metadataPath = workspaceDir.resolve("workspace.json")
        if (!metadataPath.exists()) return null
        return try {
            val content = Files.readString(metadataPath)
            json.decodeFromString<Workspace>(content)
        } catch (e: Exception) {
            log.warn("Failed to load workspace metadata from {}: {}", workspaceDir, e.message)
            null
        }
    }
}
