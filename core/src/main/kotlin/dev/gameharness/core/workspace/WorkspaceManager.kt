package dev.gameharness.core.workspace

import dev.gameharness.core.model.*
import dev.gameharness.core.util.SplitTileInfo
import dev.gameharness.core.util.ensureDirectoryExists
import dev.gameharness.core.util.readBytesSafely
import dev.gameharness.core.util.writeBytesSafely
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    companion object {
        /** File extensions recognized for auto-import, keyed by asset subdirectory. */
        val RECOGNIZED_EXTENSIONS: Map<String, Set<String>> = mapOf(
            "sprites" to setOf("png", "webp", "jpg", "jpeg"),
            "models"  to setOf("glb"),
            "music"   to setOf("mp3", "wav", "ogg"),
            "sfx"     to setOf("mp3", "wav", "ogg")
        )
    }

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

    // --- Filesystem sync ---

    /**
     * Synchronizes workspace asset metadata with what actually exists on disk.
     *
     * Performs a two-pass reconciliation:
     * 1. **Remove stale** — drops any [GeneratedAsset] entries whose [GeneratedAsset.filePath]
     *    no longer exists on disk (deleted externally).
     * 2. **Discover new** — scans each `assets/<subdirectory>/` for files with recognized
     *    extensions that are not already tracked, and imports them as new assets with
     *    [AssetDecision.APPROVED] status.
     *
     * If nothing changed, returns the original [workspace] instance (same reference)
     * so callers can use referential equality (`===`) to skip unnecessary updates.
     */
    fun syncAssets(workspace: Workspace): Workspace {
        val workspaceDir = Path.of(workspace.directoryPath)
        if (!workspaceDir.exists()) return workspace

        // --- Migration: move misplaced flat files to their correct folder directories ---
        var migratedCount = 0
        val migratedAssets = workspace.assets.map { asset ->
            if (asset.folder.isNotEmpty()) {
                val currentPath = Path.of(asset.filePath)
                val expectedDir = resolveAssetDir(workspaceDir, asset.type, asset.folder)
                val expectedPath = expectedDir.resolve(asset.fileName)

                if (currentPath.exists() && currentPath.toAbsolutePath().normalize() != expectedPath.toAbsolutePath().normalize()) {
                    // File is at the wrong location — migrate it
                    expectedDir.ensureDirectoryExists()
                    val targetPath = resolveUniqueFileName(expectedDir, asset.fileName)
                    Files.move(currentPath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                    migratedCount++
                    asset.copy(
                        fileName = targetPath.fileName.toString(),
                        filePath = targetPath.toString()
                    )
                } else {
                    asset
                }
            } else {
                asset
            }
        }

        // Ensure real directories exist for all registered folders
        for (folder in workspace.folders) {
            for (assetType in AssetType.entries) {
                resolveAssetDir(workspaceDir, assetType, folder).ensureDirectoryExists()
            }
        }

        // Pass 1: Remove stale assets whose files were deleted externally
        val liveAssets = migratedAssets.filter { asset ->
            Path.of(asset.filePath).exists()
        }
        val removedCount = migratedAssets.size - liveAssets.size

        // Build a set of normalized known paths for O(1) lookup in pass 2
        val knownPaths = liveAssets.map { asset ->
            Path.of(asset.filePath).toAbsolutePath().normalize().toString()
        }.toSet()

        // Pass 2: Discover new files in asset directories (recursive)
        val discovered = mutableListOf<GeneratedAsset>()
        val discoveredFolders = mutableSetOf<String>()

        for (assetType in AssetType.entries) {
            val subDir = workspaceDir.resolve("assets").resolve(assetType.subdirectory)
            if (!subDir.exists() || !subDir.isDirectory()) continue

            val allowedExtensions = RECOGNIZED_EXTENSIONS[assetType.subdirectory] ?: continue

            Files.walk(subDir).use { stream ->
                stream.forEach { path ->
                    if (Files.isRegularFile(path)) {
                        val fileName = path.fileName.toString()
                        val extension = fileName.substringAfterLast('.', "").lowercase()

                        if (extension in allowedExtensions) {
                            val normalizedPath = path.toAbsolutePath().normalize().toString()
                            if (normalizedPath !in knownPaths) {
                                // Compute folder from relative path within the type subdirectory
                                val relativePath = subDir.relativize(path.parent).toString().replace("\\", "/")
                                val folder = if (relativePath == "." || relativePath.isEmpty()) "" else relativePath

                                discovered.add(
                                    GeneratedAsset(
                                        id = java.util.UUID.randomUUID().toString(),
                                        type = assetType,
                                        fileName = fileName,
                                        filePath = normalizedPath,
                                        format = extension,
                                        description = "Imported from disk",
                                        sizeBytes = Files.size(path),
                                        status = AssetDecision.APPROVED,
                                        folder = folder
                                    )
                                )
                            }
                        }
                    } else if (Files.isDirectory(path) && path != subDir) {
                        // Discover subdirectories as folders
                        val relativePath = subDir.relativize(path).toString().replace("\\", "/")
                        if (relativePath.isNotEmpty() && relativePath != ".") {
                            discoveredFolders.add(relativePath)
                        }
                    }
                }
            }
        }

        // If nothing changed, return the same instance for referential equality
        val newFolders = discoveredFolders - workspace.folders
        if (removedCount == 0 && discovered.isEmpty() && migratedCount == 0 && newFolders.isEmpty()) {
            return workspace
        }

        val updated = workspace.copy(
            assets = liveAssets + discovered,
            folders = workspace.folders + newFolders
        )
        saveWorkspaceMetadata(updated)

        if (migratedCount > 0) {
            log.info("Migrated {} asset(s) to folder directories in workspace '{}'", migratedCount, workspace.name)
        }
        if (removedCount > 0) {
            log.info("Removed {} stale asset(s) from workspace '{}'", removedCount, workspace.name)
        }
        if (discovered.isNotEmpty()) {
            log.info("Discovered {} new asset(s) in workspace '{}'", discovered.size, workspace.name)
        }
        if (newFolders.isNotEmpty()) {
            log.info("Discovered {} new folder(s) in workspace '{}'", newFolders.size, workspace.name)
        }

        return updated
    }

    // --- Asset management ---

    /**
     * Saves an asset's bytes to disk and adds it to the workspace.
     * Returns a pair of (saved file path, updated workspace).
     */
    fun saveAsset(workspace: Workspace, asset: GeneratedAsset, bytes: ByteArray): Pair<Path, Workspace> {
        val workspaceDir = Path.of(workspace.directoryPath)
        val assetDir = resolveAssetDir(workspaceDir, asset.type, asset.folder)
        assetDir.ensureDirectoryExists()

        val targetPath = assetDir.resolve(asset.fileName)
        targetPath.writeBytesSafely(bytes)

        val savedAsset = asset.copy(filePath = targetPath.toString(), sizeBytes = bytes.size.toLong())
        val updated = workspace.copy(assets = workspace.assets + savedAsset)
        saveWorkspaceMetadata(updated)

        return targetPath to updated
    }

    /**
     * Saves multiple tiles from a sprite sheet split as individual assets.
     *
     * Each tile is written as a PNG to `assets/sprites/` and registered as a new
     * [GeneratedAsset] with [AssetDecision.APPROVED] status. The tiles inherit
     * the [originalAsset]'s virtual folder. Returns the updated workspace with
     * all new assets appended.
     *
     * @param baseName the file name stem used for tile naming (e.g. `"hero_idle"`
     *     produces `hero_idle_tile_0_0.png`, `hero_idle_tile_0_1.png`, etc.)
     */
    fun saveSplitAssets(
        workspace: Workspace,
        originalAsset: GeneratedAsset,
        tiles: List<Pair<SplitTileInfo, ByteArray>>,
        baseName: String
    ): Workspace {
        val workspaceDir = Path.of(workspace.directoryPath)
        val assetDir = resolveAssetDir(workspaceDir, AssetType.SPRITE, originalAsset.folder)
        assetDir.ensureDirectoryExists()

        val newAssets = mutableListOf<GeneratedAsset>()

        for ((tileInfo, bytes) in tiles) {
            val fileName = "${baseName}_tile_${tileInfo.row}_${tileInfo.col}.png"
            val targetPath = assetDir.resolve(fileName)
            targetPath.writeBytesSafely(bytes)

            val asset = GeneratedAsset(
                id = java.util.UUID.randomUUID().toString(),
                type = AssetType.SPRITE,
                fileName = fileName,
                filePath = targetPath.toString(),
                format = "png",
                description = "Split from ${originalAsset.fileName} (row ${tileInfo.row}, col ${tileInfo.col})",
                generationParams = mapOf(
                    "source" to originalAsset.fileName,
                    "tileWidth" to tileInfo.width.toString(),
                    "tileHeight" to tileInfo.height.toString(),
                    "row" to tileInfo.row.toString(),
                    "col" to tileInfo.col.toString()
                ),
                sizeBytes = bytes.size.toLong(),
                status = AssetDecision.APPROVED,
                folder = originalAsset.folder
            )
            newAssets.add(asset)
        }

        val updated = workspace.copy(assets = workspace.assets + newAssets)
        saveWorkspaceMetadata(updated)
        log.info("Saved {} split tiles from '{}' in workspace '{}'", newAssets.size, originalAsset.fileName, workspace.name)
        return updated
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

        val workspaceDir = Path.of(workspace.directoryPath)
        var movedCount = 0
        val updatedAssets = workspace.assets.map { asset ->
            if (asset.id in assetIds) {
                movedCount++
                val oldPath = Path.of(asset.filePath)
                val targetDir = resolveAssetDir(workspaceDir, asset.type, normalized)
                targetDir.ensureDirectoryExists()

                if (oldPath.exists()) {
                    val newPath = resolveUniqueFileName(targetDir, asset.fileName)
                    Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING)
                    asset.copy(
                        folder = normalized,
                        fileName = newPath.fileName.toString(),
                        filePath = newPath.toString()
                    )
                } else {
                    asset.copy(folder = normalized)
                }
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

        // Create real directories under each asset type subdirectory
        val workspaceDir = Path.of(workspace.directoryPath)
        for (folder in newFolders) {
            for (assetType in AssetType.entries) {
                resolveAssetDir(workspaceDir, assetType, folder).ensureDirectoryExists()
            }
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

        val workspaceDir = Path.of(workspace.directoryPath)
        val parentFolder = normalized.substringBeforeLast("/", "")

        // Move affected assets' files to the parent directory on disk
        val updatedAssets = workspace.assets.map { asset ->
            if (asset.folder == normalized || asset.folder.startsWith("$normalized/")) {
                val oldPath = Path.of(asset.filePath)
                val parentDir = resolveAssetDir(workspaceDir, asset.type, parentFolder)
                parentDir.ensureDirectoryExists()

                if (oldPath.exists()) {
                    val newPath = resolveUniqueFileName(parentDir, oldPath.fileName.toString())
                    Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING)
                    asset.copy(
                        folder = parentFolder,
                        fileName = newPath.fileName.toString(),
                        filePath = newPath.toString()
                    )
                } else {
                    asset.copy(folder = parentFolder)
                }
            } else {
                asset
            }
        }

        // Remove empty directory trees under each asset type
        for (assetType in AssetType.entries) {
            val folderDir = resolveAssetDir(workspaceDir, assetType, normalized)
            deleteEmptyDirectoryTree(folderDir)
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

        // Rename the real directories under each asset type
        val workspaceDir = Path.of(workspace.directoryPath)
        for (assetType in AssetType.entries) {
            val oldDir = resolveAssetDir(workspaceDir, assetType, normalizedOld)
            val newDir = resolveAssetDir(workspaceDir, assetType, newPath)
            if (oldDir.exists()) {
                newDir.parent?.ensureDirectoryExists()
                Files.move(oldDir, newDir, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // Update all subfolders in the set
        val updatedFolders = workspace.folders.map { folder ->
            when {
                folder == normalizedOld -> newPath
                folder.startsWith("$normalizedOld/") -> folder.replaceFirst(normalizedOld, newPath)
                else -> folder
            }
        }.toSet()

        // Update all asset folder references AND file paths
        val updatedAssets = workspace.assets.map { asset ->
            when {
                asset.folder == normalizedOld -> {
                    val newFilePath = resolveAssetDir(workspaceDir, asset.type, newPath)
                        .resolve(asset.fileName).toString()
                    asset.copy(folder = newPath, filePath = newFilePath)
                }
                asset.folder.startsWith("$normalizedOld/") -> {
                    val newFolder = asset.folder.replaceFirst(normalizedOld, newPath)
                    val newFilePath = resolveAssetDir(workspaceDir, asset.type, newFolder)
                        .resolve(asset.fileName).toString()
                    asset.copy(folder = newFolder, filePath = newFilePath)
                }
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

        val workspaceDir = Path.of(workspace.directoryPath)
        val updatedAssets = workspace.assets.mapIndexed { i, asset ->
            if (i == index) {
                val oldPath = Path.of(asset.filePath)
                val targetDir = resolveAssetDir(workspaceDir, asset.type, normalized)
                targetDir.ensureDirectoryExists()

                if (oldPath.exists()) {
                    val newPath = resolveUniqueFileName(targetDir, asset.fileName)
                    Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING)
                    asset.copy(
                        folder = normalized,
                        fileName = newPath.fileName.toString(),
                        filePath = newPath.toString()
                    )
                } else {
                    asset.copy(folder = normalized)
                }
            } else {
                asset
            }
        }
        val updated = workspace.copy(assets = updatedAssets)
        saveWorkspaceMetadata(updated)
        log.info("Moved asset '{}' to folder '{}' in workspace '{}'", assetId, normalized, workspace.name)
        return updated
    }

    private fun normalizeFolderPath(path: String): String {
        return path.trim().trim('/').replace("\\", "/")
    }

    /**
     * Resolves a unique file name in [directory], appending `_1`, `_2`, etc.
     * before the extension if a file with the given [fileName] already exists.
     */
    private fun resolveUniqueFileName(directory: Path, fileName: String): Path {
        var target = directory.resolve(fileName)
        if (!target.exists()) return target

        val baseName = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        var counter = 1
        while (target.exists()) {
            val newName = if (extension.isNotEmpty()) "${baseName}_$counter.$extension" else "${baseName}_$counter"
            target = directory.resolve(newName)
            counter++
        }
        return target
    }

    /**
     * Recursively deletes empty directories within [dir], walking bottom-up.
     * Stops at directories that still contain files.
     */
    private fun deleteEmptyDirectoryTree(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .filter { Files.isDirectory(it) }
                .forEach { d ->
                    try {
                        Files.list(d).use { contents ->
                            if (contents.findFirst().isEmpty) {
                                Files.delete(d)
                            }
                        }
                    } catch (_: Exception) { }
                }
        }
    }

    /**
     * Resolves the asset directory for a given asset type and folder within a workspace.
     * Returns `assets/<type>/` for root or `assets/<type>/<folder>/` for non-empty folders.
     */
    private fun resolveAssetDir(workspaceDir: Path, type: AssetType, folder: String): Path {
        val baseDir = workspaceDir.resolve("assets").resolve(type.subdirectory)
        return if (folder.isEmpty()) baseDir else baseDir.resolve(folder)
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
