package dev.gameharness.core.workspace

import dev.gameharness.core.model.*
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
}
