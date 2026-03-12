package dev.gameharness.core.workspace

import dev.gameharness.core.model.*
import dev.gameharness.core.util.SplitTileInfo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class WorkspaceManagerTest {

    @TempDir
    lateinit var registryDir: Path

    @TempDir
    lateinit var workspacesDir: Path

    private lateinit var manager: WorkspaceManager

    @BeforeEach
    fun setup() {
        manager = WorkspaceManager(registryDir)
    }

    @Test
    fun `creates workspace with correct directory structure`() {
        val wsDir = workspacesDir.resolve("my-rpg")
        val workspace = manager.createWorkspace("My RPG", wsDir)

        assertEquals("My RPG", workspace.name)
        assertTrue(Path.of(workspace.directoryPath).toFile().exists())

        // Verify asset subdirectories were created
        AssetType.entries.forEach { type ->
            val assetDir = Path.of(workspace.directoryPath, "assets", type.subdirectory)
            assertTrue(assetDir.toFile().exists(), "Missing directory: ${type.subdirectory}")
        }
    }

    @Test
    fun `creates workspace in new directory`() {
        val wsDir = workspacesDir.resolve("brand-new")
        assertFalse(wsDir.toFile().exists())

        val workspace = manager.createWorkspace("Brand New", wsDir)
        assertTrue(Path.of(workspace.directoryPath).toFile().exists())
    }

    @Test
    fun `creates workspace in existing empty directory`() {
        val wsDir = workspacesDir.resolve("empty-dir")
        Files.createDirectories(wsDir)

        val workspace = manager.createWorkspace("Empty Dir", wsDir)
        assertEquals("Empty Dir", workspace.name)
    }

    @Test
    fun `rejects non-empty directory`() {
        val wsDir = workspacesDir.resolve("occupied")
        Files.createDirectories(wsDir)
        Files.writeString(wsDir.resolve("existing.txt"), "data")

        assertFailsWith<IllegalArgumentException> {
            manager.createWorkspace("Test", wsDir)
        }
    }

    @Test
    fun `rejects blank workspace name`() {
        assertFailsWith<IllegalArgumentException> {
            manager.createWorkspace("", workspacesDir.resolve("blank"))
        }
    }

    @Test
    fun `lists created workspaces from registry`() {
        manager.createWorkspace("Project A", workspacesDir.resolve("proj-a"))
        manager.createWorkspace("Project B", workspacesDir.resolve("proj-b"))

        val workspaces = manager.listWorkspaces()
        assertEquals(2, workspaces.size)

        val names = workspaces.map { it.name }.toSet()
        assertTrue("Project A" in names)
        assertTrue("Project B" in names)
    }

    @Test
    fun `lists empty when no workspaces`() {
        val workspaces = manager.listWorkspaces()
        assertTrue(workspaces.isEmpty())
    }

    @Test
    fun `stale registry entries are auto-removed`() {
        val wsDir = workspacesDir.resolve("ephemeral")
        manager.createWorkspace("Ephemeral", wsDir)
        assertEquals(1, manager.listWorkspaces().size)

        // Simulate deletion outside the app
        Files.walk(wsDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }

        assertEquals(0, manager.listWorkspaces().size)
    }

    @Test
    fun `gets workspace by name`() {
        manager.createWorkspace("FindMe", workspacesDir.resolve("find-me"))

        val found = manager.getWorkspace("FindMe")
        assertNotNull(found)
        assertEquals("FindMe", found.name)
    }

    @Test
    fun `returns null for nonexistent workspace`() {
        assertNull(manager.getWorkspace("nonexistent"))
    }

    @Test
    fun `rename only changes metadata not directory`() {
        val wsDir = workspacesDir.resolve("original")
        val workspace = manager.createWorkspace("Original", wsDir)
        val originalPath = workspace.directoryPath

        val renamed = manager.renameWorkspace(workspace, "Renamed")

        assertEquals("Renamed", renamed.name)
        assertEquals(originalPath, renamed.directoryPath) // path unchanged!
        assertTrue(Path.of(originalPath).toFile().exists())
    }

    @Test
    fun `saves and loads asset bytes`() {
        val workspace = manager.createWorkspace("AssetTest", workspacesDir.resolve("asset-test"))
        val testBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes
        val asset = GeneratedAsset(
            id = "asset-001",
            type = AssetType.SPRITE,
            fileName = "hero.png",
            filePath = "", // will be set by saveAsset
            format = "png",
            description = "A hero sprite"
        )

        val (savedPath, updatedWs) = manager.saveAsset(workspace, asset, testBytes)
        assertTrue(savedPath.toFile().exists())

        val savedAsset = updatedWs.assets.first()
        val loaded = manager.loadAssetBytes(savedAsset)
        assertContentEquals(testBytes, loaded)
    }

    @Test
    fun `updates asset status`() {
        val workspace = manager.createWorkspace("StatusTest", workspacesDir.resolve("status-test"))
        val asset = GeneratedAsset(
            id = "asset-002",
            type = AssetType.MUSIC,
            fileName = "battle.mp3",
            filePath = "",
            format = "mp3",
            description = "Battle music"
        )
        val (_, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))

        val updated = manager.updateAssetStatus(wsWithAsset, "asset-002", AssetDecision.APPROVED)

        assertEquals(AssetDecision.APPROVED, updated.assets.first().status)
    }

    @Test
    fun `deletes asset and removes from workspace`() {
        val workspace = manager.createWorkspace("DeleteTest", workspacesDir.resolve("delete-test"))
        val asset = GeneratedAsset(
            id = "asset-003",
            type = AssetType.SOUND_EFFECT,
            fileName = "boom.mp3",
            filePath = "",
            format = "mp3",
            description = "Explosion"
        )
        val (savedPath, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))
        assertTrue(savedPath.toFile().exists())

        val updated = manager.deleteAsset(wsWithAsset, "asset-003")

        assertFalse(savedPath.toFile().exists())
        assertTrue(updated.assets.isEmpty())
    }

    @Test
    fun `saves and loads chat history`() {
        val workspace = manager.createWorkspace("ChatTest", workspacesDir.resolve("chat-test"))
        val messages = listOf(
            ChatMessage(role = ChatRole.USER, content = "Generate a sword sprite", timestamp = 1000),
            ChatMessage(role = ChatRole.ASSISTANT, content = "Generating...", timestamp = 2000),
            ChatMessage(role = ChatRole.ASSISTANT, content = "Done!", timestamp = 3000, assetRef = "asset-001")
        )

        manager.saveChatHistory(workspace, messages)
        val loaded = manager.loadChatHistory(workspace)

        assertEquals(messages.size, loaded.size)
        assertEquals(messages[0].content, loaded[0].content)
        assertEquals(messages[2].assetRef, loaded[2].assetRef)
    }

    @Test
    fun `loads empty chat history for new workspace`() {
        val workspace = manager.createWorkspace("EmptyChatTest", workspacesDir.resolve("empty-chat"))
        val history = manager.loadChatHistory(workspace)
        assertTrue(history.isEmpty())
    }

    @Test
    fun `delete workspace removes from registry`() {
        val wsDir = workspacesDir.resolve("to-delete")
        val workspace = manager.createWorkspace("ToDelete", wsDir)
        assertEquals(1, manager.listWorkspaces().size)

        manager.deleteWorkspace(workspace)

        assertEquals(0, manager.listWorkspaces().size)
        assertFalse(wsDir.toFile().exists())
    }

    @Test
    fun `import existing workspace adds to registry`() {
        // Manually create a workspace directory with workspace.json
        val wsDir = workspacesDir.resolve("imported")
        Files.createDirectories(wsDir)
        val workspaceJson = """{"name":"Imported Project","directoryPath":"${wsDir.toAbsolutePath().toString().replace("\\", "\\\\")}","createdAt":1000,"assets":[]}"""
        Files.writeString(wsDir.resolve("workspace.json"), workspaceJson)

        val imported = manager.importWorkspace(wsDir)

        assertNotNull(imported)
        assertEquals("Imported Project", imported.name)
        assertEquals(1, manager.listWorkspaces().size)
    }

    @Test
    fun `saves and loads workspace context`() {
        val workspace = manager.createWorkspace("ContextTest", workspacesDir.resolve("context-test"))
        val instructions = "All sprites should use 16-bit pixel art style with a 64x64 resolution.\nUse a dark fantasy color palette."

        manager.saveWorkspaceContext(workspace, instructions)
        val loaded = manager.loadWorkspaceContext(workspace)

        assertEquals(instructions, loaded)
    }

    @Test
    fun `loads empty context for new workspace`() {
        val workspace = manager.createWorkspace("NoContext", workspacesDir.resolve("no-context"))
        val context = manager.loadWorkspaceContext(workspace)
        assertEquals("", context)
    }

    // ── saveSplitAssets() ───────────────────────────────────────────────

    @Test
    fun `saveSplitAssets creates files and workspace entries with APPROVED status`() {
        val workspace = manager.createWorkspace("SplitTest", workspacesDir.resolve("split-test"))
        val sourceAsset = GeneratedAsset(
            id = "source-001",
            type = AssetType.SPRITE,
            fileName = "sheet.png",
            filePath = workspacesDir.resolve("split-test/assets/sprites/sheet.png").toString(),
            format = "png",
            description = "A sprite sheet"
        )

        val tiles = listOf(
            SplitTileInfo(row = 0, col = 0, x = 0, y = 0, width = 32, height = 32) to byteArrayOf(1, 2, 3),
            SplitTileInfo(row = 0, col = 1, x = 32, y = 0, width = 32, height = 32) to byteArrayOf(4, 5, 6),
            SplitTileInfo(row = 1, col = 0, x = 0, y = 32, width = 32, height = 32) to byteArrayOf(7, 8, 9)
        )

        val updated = manager.saveSplitAssets(workspace, sourceAsset, tiles, "hero")

        // Should have 3 new assets
        assertEquals(3, updated.assets.size)

        // All should be APPROVED and of type SPRITE
        updated.assets.forEach { asset ->
            assertEquals(AssetDecision.APPROVED, asset.status)
            assertEquals(AssetType.SPRITE, asset.type)
            assertEquals("png", asset.format)
            assertTrue(asset.description.contains("Split from sheet.png"))
        }

        // File names follow the baseName_tile_row_col pattern
        val fileNames = updated.assets.map { it.fileName }.toSet()
        assertTrue("hero_tile_0_0.png" in fileNames)
        assertTrue("hero_tile_0_1.png" in fileNames)
        assertTrue("hero_tile_1_0.png" in fileNames)

        // Files should actually exist on disk
        updated.assets.forEach { asset ->
            assertTrue(Path.of(asset.filePath).toFile().exists(), "Missing file: ${asset.filePath}")
        }
    }

    @Test
    fun `saveSplitAssets inherits folder from source asset`() {
        var workspace = manager.createWorkspace("FolderSplitTest", workspacesDir.resolve("folder-split"))
        workspace = manager.createFolder(workspace, "characters")

        val sourceAsset = GeneratedAsset(
            id = "source-002",
            type = AssetType.SPRITE,
            fileName = "walk.png",
            filePath = "",
            format = "png",
            description = "Walking sprite sheet",
            folder = "characters"
        )

        val tiles = listOf(
            SplitTileInfo(row = 0, col = 0, x = 0, y = 0, width = 16, height = 16) to byteArrayOf(10, 20)
        )

        val updated = manager.saveSplitAssets(workspace, sourceAsset, tiles, "walk")

        assertEquals(1, updated.assets.size)
        assertEquals("characters", updated.assets.first().folder)
    }

    // ── syncAssets() ──────────────────────────────────────────────────────

    @Test
    fun `syncAssets removes assets whose files no longer exist`() {
        val workspace = manager.createWorkspace("SyncRemoveTest", workspacesDir.resolve("sync-remove"))
        val asset = GeneratedAsset(
            id = "stale-001",
            type = AssetType.SPRITE,
            fileName = "ghost.png",
            filePath = "",
            format = "png",
            description = "A ghost sprite"
        )
        val (savedPath, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))
        assertEquals(1, wsWithAsset.assets.size)
        assertTrue(savedPath.toFile().exists())

        // Delete the file externally
        Files.delete(savedPath)
        assertFalse(savedPath.toFile().exists())

        val synced = manager.syncAssets(wsWithAsset)

        assertTrue(synced.assets.isEmpty(), "Stale asset should be removed")
    }

    @Test
    fun `syncAssets discovers new files in asset directories`() {
        val workspace = manager.createWorkspace("SyncDiscoverTest", workspacesDir.resolve("sync-discover"))

        // Manually write a PNG file to the sprites directory
        val spritesDir = Path.of(workspace.directoryPath, "assets", "sprites")
        val newFile = spritesDir.resolve("external_sprite.png")
        Files.write(newFile, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

        val synced = manager.syncAssets(workspace)

        assertEquals(1, synced.assets.size)
        val discovered = synced.assets.first()
        assertEquals("external_sprite.png", discovered.fileName)
        assertEquals(AssetType.SPRITE, discovered.type)
        assertEquals("png", discovered.format)
    }

    @Test
    fun `syncAssets ignores files with unrecognized extensions`() {
        val workspace = manager.createWorkspace("SyncIgnoreTest", workspacesDir.resolve("sync-ignore"))

        // Write a .txt file to the sprites directory — should be ignored
        val spritesDir = Path.of(workspace.directoryPath, "assets", "sprites")
        Files.write(spritesDir.resolve("readme.txt"), "Not a sprite".toByteArray())

        val synced = manager.syncAssets(workspace)

        assertTrue(synced.assets.isEmpty(), "Unrecognized extension should be ignored")
    }

    @Test
    fun `syncAssets returns same instance when nothing changed`() {
        val workspace = manager.createWorkspace("SyncNoopTest", workspacesDir.resolve("sync-noop"))
        val asset = GeneratedAsset(
            id = "existing-001",
            type = AssetType.SPRITE,
            fileName = "stable.png",
            filePath = "",
            format = "png",
            description = "A stable sprite"
        )
        val (_, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))

        val synced = manager.syncAssets(wsWithAsset)

        assertSame(wsWithAsset, synced, "Should return same instance when nothing changed")
    }

    @Test
    fun `syncAssets handles both stale removal and new discovery in one call`() {
        val workspace = manager.createWorkspace("SyncBothTest", workspacesDir.resolve("sync-both"))
        val assetA = GeneratedAsset(
            id = "keep-001", type = AssetType.SPRITE, fileName = "keep.png",
            filePath = "", format = "png", description = "Will stay"
        )
        val assetB = GeneratedAsset(
            id = "stale-002", type = AssetType.SPRITE, fileName = "stale.png",
            filePath = "", format = "png", description = "Will be deleted"
        )

        val (_, ws1) = manager.saveAsset(workspace, assetA, byteArrayOf(1, 2))
        val (stalePath, ws2) = manager.saveAsset(ws1, assetB, byteArrayOf(3, 4))

        // Delete stale file externally
        Files.delete(stalePath)

        // Add a new file externally
        val spritesDir = Path.of(ws2.directoryPath, "assets", "sprites")
        Files.write(spritesDir.resolve("newcomer.png"), byteArrayOf(5, 6, 7))

        val synced = manager.syncAssets(ws2)

        // Should have 2 assets: the surviving original + the discovered newcomer
        assertEquals(2, synced.assets.size)
        val fileNames = synced.assets.map { it.fileName }.toSet()
        assertTrue("keep.png" in fileNames, "Surviving asset should remain")
        assertTrue("newcomer.png" in fileNames, "New file should be discovered")
        assertFalse("stale.png" in fileNames, "Stale asset should be removed")
    }

    @Test
    fun `syncAssets sets correct defaults on discovered assets`() {
        val workspace = manager.createWorkspace("SyncDefaultsTest", workspacesDir.resolve("sync-defaults"))

        // Add a music file externally
        val musicDir = Path.of(workspace.directoryPath, "assets", "music")
        val musicBytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
        Files.write(musicDir.resolve("external_track.mp3"), musicBytes)

        val synced = manager.syncAssets(workspace)

        assertEquals(1, synced.assets.size)
        val discovered = synced.assets.first()
        assertEquals(AssetDecision.APPROVED, discovered.status)
        assertEquals("", discovered.folder)
        assertEquals("Imported from disk", discovered.description)
        assertEquals(musicBytes.size.toLong(), discovered.sizeBytes)
        assertEquals(AssetType.MUSIC, discovered.type)
        assertEquals("mp3", discovered.format)
        assertTrue(discovered.id.isNotBlank(), "Should have a generated ID")
    }

    // ── Physical folder operations ──────────────────────────────────────

    @Test
    fun `createFolder creates real directories under each asset type`() {
        val workspace = manager.createWorkspace("FolderDirTest", workspacesDir.resolve("folder-dir"))
        val updated = manager.createFolder(workspace, "characters")

        assertTrue("characters" in updated.folders)

        // Verify real directories exist under each asset type
        val wsDir = Path.of(updated.directoryPath)
        for (assetType in AssetType.entries) {
            val folderDir = wsDir.resolve("assets").resolve(assetType.subdirectory).resolve("characters")
            assertTrue(folderDir.toFile().exists(), "Missing directory: assets/${assetType.subdirectory}/characters")
            assertTrue(folderDir.toFile().isDirectory, "Not a directory: assets/${assetType.subdirectory}/characters")
        }
    }

    @Test
    fun `createFolder with nested path creates all parent directories`() {
        val workspace = manager.createWorkspace("NestedFolderTest", workspacesDir.resolve("nested-folder"))
        val updated = manager.createFolder(workspace, "enemies/bosses")

        assertTrue("enemies" in updated.folders)
        assertTrue("enemies/bosses" in updated.folders)

        // Verify nested directories exist
        val wsDir = Path.of(updated.directoryPath)
        val spritesDir = wsDir.resolve("assets").resolve("sprites")
        assertTrue(spritesDir.resolve("enemies").toFile().isDirectory)
        assertTrue(spritesDir.resolve("enemies").resolve("bosses").toFile().isDirectory)
    }

    @Test
    fun `saveAsset respects folder path on disk`() {
        var workspace = manager.createWorkspace("SaveFolderTest", workspacesDir.resolve("save-folder"))
        workspace = manager.createFolder(workspace, "items")

        val asset = GeneratedAsset(
            id = "folder-asset-001",
            type = AssetType.SPRITE,
            fileName = "sword.png",
            filePath = "",
            format = "png",
            description = "A sword sprite",
            folder = "items"
        )

        val (savedPath, updated) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))

        // File should be saved inside the items subfolder
        assertTrue(savedPath.toFile().exists())
        assertTrue(savedPath.toString().replace("\\", "/").contains("assets/sprites/items/sword.png"))

        val savedAsset = updated.assets.first()
        assertEquals("items", savedAsset.folder)
        assertTrue(savedAsset.filePath.replace("\\", "/").contains("assets/sprites/items/sword.png"))
    }

    @Test
    fun `saveSplitAssets writes tiles to folder directory`() {
        var workspace = manager.createWorkspace("SplitFolderDiskTest", workspacesDir.resolve("split-folder-disk"))
        workspace = manager.createFolder(workspace, "animations")

        val sourceAsset = GeneratedAsset(
            id = "source-folder-001",
            type = AssetType.SPRITE,
            fileName = "run.png",
            filePath = "",
            format = "png",
            description = "Run sheet",
            folder = "animations"
        )

        val tiles = listOf(
            SplitTileInfo(row = 0, col = 0, x = 0, y = 0, width = 32, height = 32) to byteArrayOf(1, 2, 3)
        )

        val updated = manager.saveSplitAssets(workspace, sourceAsset, tiles, "run")
        val tileAsset = updated.assets.first()

        // File should be physically inside the animations folder
        assertTrue(Path.of(tileAsset.filePath).toFile().exists())
        assertTrue(tileAsset.filePath.replace("\\", "/").contains("assets/sprites/animations/run_tile_0_0.png"))
        assertEquals("animations", tileAsset.folder)
    }

    @Test
    fun `moveAssetToFolder physically moves file to folder directory`() {
        var workspace = manager.createWorkspace("MovePhysicalTest", workspacesDir.resolve("move-physical"))
        workspace = manager.createFolder(workspace, "weapons")

        val asset = GeneratedAsset(
            id = "move-001",
            type = AssetType.SPRITE,
            fileName = "axe.png",
            filePath = "",
            format = "png",
            description = "An axe"
        )
        val (savedPath, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))
        assertTrue(savedPath.toFile().exists())

        val moved = manager.moveAssetToFolder(wsWithAsset, "move-001", "weapons")

        // Old file should be gone, new file in the weapons subdirectory
        assertFalse(savedPath.toFile().exists(), "Original file should be removed after move")
        val movedAsset = moved.assets.first()
        assertEquals("weapons", movedAsset.folder)
        assertTrue(Path.of(movedAsset.filePath).toFile().exists(), "Moved file should exist")
        assertTrue(movedAsset.filePath.replace("\\", "/").contains("assets/sprites/weapons/axe.png"))
    }

    @Test
    fun `moveAssetToFolder back to root moves file out of folder`() {
        var workspace = manager.createWorkspace("MoveToRootTest", workspacesDir.resolve("move-root"))
        workspace = manager.createFolder(workspace, "items")

        val asset = GeneratedAsset(
            id = "root-001",
            type = AssetType.SPRITE,
            fileName = "shield.png",
            filePath = "",
            format = "png",
            description = "A shield",
            folder = "items"
        )
        val (savedPath, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(4, 5, 6))
        assertTrue(savedPath.toString().replace("\\", "/").contains("items/shield.png"))

        val moved = manager.moveAssetToFolder(wsWithAsset, "root-001", "")

        assertFalse(savedPath.toFile().exists(), "File should be moved from items folder")
        val movedAsset = moved.assets.first()
        assertEquals("", movedAsset.folder)
        assertTrue(Path.of(movedAsset.filePath).toFile().exists())
        assertFalse(movedAsset.filePath.replace("\\", "/").contains("items/"))
    }

    @Test
    fun `moveAssetToFolder handles filename conflict with unique naming`() {
        var workspace = manager.createWorkspace("MoveConflictTest", workspacesDir.resolve("move-conflict"))
        workspace = manager.createFolder(workspace, "shared")

        // Save two assets with the same filename in different folders
        val asset1 = GeneratedAsset(
            id = "conflict-001", type = AssetType.SPRITE, fileName = "hero.png",
            filePath = "", format = "png", description = "Hero 1", folder = ""
        )
        val asset2 = GeneratedAsset(
            id = "conflict-002", type = AssetType.SPRITE, fileName = "hero.png",
            filePath = "", format = "png", description = "Hero 2", folder = "shared"
        )

        val (_, ws1) = manager.saveAsset(workspace, asset1, byteArrayOf(1, 2))
        val (_, ws2) = manager.saveAsset(ws1, asset2, byteArrayOf(3, 4))

        // Move asset1 (root hero.png) to "shared" which already has hero.png
        val moved = manager.moveAssetToFolder(ws2, "conflict-001", "shared")

        val movedAsset = moved.assets.find { it.id == "conflict-001" }!!
        assertEquals("shared", movedAsset.folder)
        assertTrue(Path.of(movedAsset.filePath).toFile().exists())
        // Should have been renamed to avoid collision
        assertEquals("hero_1.png", movedAsset.fileName)
    }

    @Test
    fun `moveAssetsToFolder batch moves files physically`() {
        var workspace = manager.createWorkspace("BatchMoveTest", workspacesDir.resolve("batch-move"))
        workspace = manager.createFolder(workspace, "batch")

        val assetA = GeneratedAsset(
            id = "batch-001", type = AssetType.SPRITE, fileName = "a.png",
            filePath = "", format = "png", description = "A"
        )
        val assetB = GeneratedAsset(
            id = "batch-002", type = AssetType.SPRITE, fileName = "b.png",
            filePath = "", format = "png", description = "B"
        )

        val (pathA, ws1) = manager.saveAsset(workspace, assetA, byteArrayOf(1))
        val (pathB, ws2) = manager.saveAsset(ws1, assetB, byteArrayOf(2))

        val moved = manager.moveAssetsToFolder(ws2, setOf("batch-001", "batch-002"), "batch")

        // Both original files should be gone
        assertFalse(pathA.toFile().exists())
        assertFalse(pathB.toFile().exists())

        // Both moved files should exist in the batch folder
        moved.assets.forEach { asset ->
            assertEquals("batch", asset.folder)
            assertTrue(Path.of(asset.filePath).toFile().exists())
            assertTrue(asset.filePath.replace("\\", "/").contains("assets/sprites/batch/"))
        }
    }

    @Test
    fun `deleteFolder moves files to parent directory on disk`() {
        var workspace = manager.createWorkspace("DeleteFolderPhysTest", workspacesDir.resolve("del-folder-phys"))
        workspace = manager.createFolder(workspace, "obsolete")

        val asset = GeneratedAsset(
            id = "delfolder-001", type = AssetType.SPRITE, fileName = "old.png",
            filePath = "", format = "png", description = "Old sprite", folder = "obsolete"
        )
        val (savedPath, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))
        assertTrue(savedPath.toString().replace("\\", "/").contains("obsolete/old.png"))

        val deleted = manager.deleteFolder(wsWithAsset, "obsolete")

        // Asset should now be at root
        val movedAsset = deleted.assets.first()
        assertEquals("", movedAsset.folder)
        assertTrue(Path.of(movedAsset.filePath).toFile().exists(), "File should exist at new location")
        assertFalse(movedAsset.filePath.replace("\\", "/").contains("obsolete/"))

        // Old file location should be gone
        assertFalse(savedPath.toFile().exists(), "Original file should no longer exist")
    }

    @Test
    fun `deleteFolder removes empty folder directories`() {
        var workspace = manager.createWorkspace("DeleteEmptyDirTest", workspacesDir.resolve("del-empty-dir"))
        workspace = manager.createFolder(workspace, "empty-folder")

        val wsDir = Path.of(workspace.directoryPath)
        val spriteFolderDir = wsDir.resolve("assets").resolve("sprites").resolve("empty-folder")
        assertTrue(spriteFolderDir.toFile().isDirectory, "Folder directory should exist before delete")

        manager.deleteFolder(workspace, "empty-folder")

        // The empty folder directory should be cleaned up
        assertFalse(spriteFolderDir.toFile().exists(), "Empty folder directory should be removed")
    }

    @Test
    fun `renameFolder renames directories and updates filePaths`() {
        var workspace = manager.createWorkspace("RenameFolderPhysTest", workspacesDir.resolve("rename-folder-phys"))
        workspace = manager.createFolder(workspace, "old-name")

        val asset = GeneratedAsset(
            id = "rename-001", type = AssetType.SPRITE, fileName = "sprite.png",
            filePath = "", format = "png", description = "A sprite", folder = "old-name"
        )
        val (_, wsWithAsset) = manager.saveAsset(workspace, asset, byteArrayOf(1, 2, 3))

        val renamed = manager.renameFolder(wsWithAsset, "old-name", "new-name")

        // Directory should be renamed
        val wsDir = Path.of(renamed.directoryPath)
        val oldDir = wsDir.resolve("assets").resolve("sprites").resolve("old-name")
        val newDir = wsDir.resolve("assets").resolve("sprites").resolve("new-name")
        assertFalse(oldDir.toFile().exists(), "Old directory should not exist")
        assertTrue(newDir.toFile().exists(), "New directory should exist")

        // Asset should have updated folder and filePath
        val renamedAsset = renamed.assets.first()
        assertEquals("new-name", renamedAsset.folder)
        assertTrue(renamedAsset.filePath.replace("\\", "/").contains("new-name/sprite.png"))
        assertTrue(Path.of(renamedAsset.filePath).toFile().exists(), "File should exist at renamed path")
    }

    @Test
    fun `syncAssets discovers files in subdirectories with correct folder`() {
        val workspace = manager.createWorkspace("SyncSubdirTest", workspacesDir.resolve("sync-subdir"))

        // Manually create a subdirectory with a file
        val wsDir = Path.of(workspace.directoryPath)
        val characterDir = wsDir.resolve("assets").resolve("sprites").resolve("characters")
        Files.createDirectories(characterDir)
        Files.write(characterDir.resolve("warrior.png"), byteArrayOf(1, 2, 3, 4))

        val synced = manager.syncAssets(workspace)

        assertEquals(1, synced.assets.size)
        val discovered = synced.assets.first()
        assertEquals("warrior.png", discovered.fileName)
        assertEquals("characters", discovered.folder)
        assertEquals(AssetType.SPRITE, discovered.type)

        // The "characters" folder should be auto-discovered
        assertTrue("characters" in synced.folders)
    }

    @Test
    fun `syncAssets migrates flat files to folder directories`() {
        var workspace = manager.createWorkspace("SyncMigrateTest", workspacesDir.resolve("sync-migrate"))
        workspace = manager.createFolder(workspace, "heroes")

        // Simulate legacy state: asset has folder="heroes" but file is in the flat sprites root
        val wsDir = Path.of(workspace.directoryPath)
        val flatPath = wsDir.resolve("assets").resolve("sprites").resolve("knight.png")
        Files.write(flatPath, byteArrayOf(10, 20, 30))

        val legacyAsset = GeneratedAsset(
            id = "migrate-001", type = AssetType.SPRITE, fileName = "knight.png",
            filePath = flatPath.toString(), format = "png",
            description = "A knight", folder = "heroes"
        )
        val wsWithLegacy = workspace.copy(assets = listOf(legacyAsset))

        val synced = manager.syncAssets(wsWithLegacy)

        // The file should now be in the heroes subdirectory
        val migratedAsset = synced.assets.find { it.id == "migrate-001" }
        assertNotNull(migratedAsset)
        assertEquals("heroes", migratedAsset.folder)
        assertTrue(migratedAsset.filePath.replace("\\", "/").contains("assets/sprites/heroes/knight.png"))
        assertTrue(Path.of(migratedAsset.filePath).toFile().exists(), "Migrated file should exist at new location")
        assertFalse(flatPath.toFile().exists(), "Original flat file should be moved")
    }
}
