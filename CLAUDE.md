# GameDeveloperHarness - Project Context

## What This Project Is

A Kotlin Compose Desktop application that lets game developers generate game assets (2D sprites, 3D models, music, sound effects) through an AI-powered chat interface. The user describes what they want, the AI agent interprets the request, calls the appropriate generation API, and presents the result for approval/denial.

## Architecture

4 Gradle modules with a linear dependency chain:

```
:core  -->  :api-clients  -->  :agent  -->  :gui
```

- **`:core`** - Data models, config, workspace management, file utilities
- **`:api-clients`** - HTTP clients for external generation APIs (Gemini, Meshy, Suno, ElevenLabs)
- **`:agent`** - KOOG AI agent framework integration, tools, bridge for UI communication
- **`:gui`** - Compose Desktop UI (Material 3), theming, viewmodel, main entry point

Package root: `dev.gameharness`

## Tech Stack

| Component | Version | Notes |
|-----------|---------|-------|
| Kotlin | 2.3.0 | Required by KOOG 0.6.3 (kotlinx-serialization 1.10.0) |
| KOOG | 0.6.3 | JetBrains AI agent framework |
| Compose Desktop | 1.8.2 | Material 3 UI toolkit |
| Ktor | 3.1.3 | HTTP client for API calls |
| JDK | 21 | `jvmToolchain(21)` in every module |
| SLF4J + Logback | 2.0.16 / 1.5.16 | Logging to `~/.gameharness/logs/` |

## Build & Run

```bash
# Set JDK 21 (Android Studio bundled JBR on this machine)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"

# Build everything + run all tests
./gradlew build

# Run the application
./gradlew :gui:run

# Run tests for a specific module
./gradlew :core:test
./gradlew :api-clients:test
./gradlew :agent:test

# Create native installer (MSI on Windows, DEB on Linux)
./gradlew :gui:packageMsi
./gradlew :gui:packageDeb
```

## External APIs & Keys

| Service | Asset Type | Key Config Name | Endpoint |
|---------|-----------|-----------------|----------|
| **OpenRouter** | LLM reasoning (agent brain) | `OPENROUTER_API_KEY` | OpenRouter API (routes to Claude 4.5 Sonnet) |
| **NanoBanana (Gemini)** | 2D Sprites/Textures | `GEMINI_API_KEY` | `generativelanguage.googleapis.com/v1beta/models/{model}` |
| **Meshy** | 3D Models (GLB/FBX/OBJ) | `MESHY_API_KEY` | `api.meshy.ai` (text-to-3D: 2-stage preview+refine; image-to-3D: single-stage) |
| **Suno** | Music (MP3) | `SUNO_API_KEY` | Suno API (create + poll until complete) |
| **ElevenLabs** | Sound Effects (MP3) | `ELEVENLABS_API_KEY` | `api.elevenlabs.io/v1/sound-generation` |

- **OpenRouter is required.** All others are optional; the agent adapts its available tools based on which keys are configured.
- Keys can be set via environment variables OR the in-app Settings dialog (persisted to `~/.gameharness/settings.json`).
- **NanoBanana model selector**: When a Gemini key is configured, users can choose between three models in Settings:
  - `Nano Banana` → `gemini-2.5-flash-image` (default, fast)
  - `Nano Banana 2` → `gemini-3.1-flash-image-preview` (higher fidelity)
  - `Nano Banana Pro` → `gemini-3-pro-image-preview` (up to 4K, best quality)
- Model selection is stored in `SavedSettings.nanoBananaModel` and flows through `AppConfig` → `GameAgent` → `GeminiClient`.

## Key File Locations

### Core Models
- `core/.../core/model/` - AssetType, GeneratedAsset, GenerationRequest/Result, ChatMessage, Workspace
- `core/.../core/config/AppConfig.kt` - Runtime config, key validation, capability detection
- `core/.../core/config/SettingsManager.kt` - Persistent settings to `~/.gameharness/settings.json`
- `core/.../core/workspace/WorkspaceManager.kt` - Workspace CRUD, asset I/O, chat history, workspace context
- `core/.../core/workspace/WorkspaceRegistry.kt` - JSON registry tracking workspace paths across filesystem

### API Clients
- `api-clients/.../api/common/AssetGenerationClient.kt` - Interface all clients implement
- `api-clients/.../api/common/RateLimiter.kt` - Token bucket rate limiter
- `api-clients/.../api/gemini/GeminiClient.kt` - 2D sprite generation via Gemini
- `api-clients/.../api/meshy/MeshyClient.kt` - 3D model generation (preview + refine pipeline)
- `api-clients/.../api/suno/SunoClient.kt` - Music generation (create + poll pipeline)
- `api-clients/.../api/elevenlabs/ElevenLabsClient.kt` - Sound effect generation (single-shot)

### Agent
- `agent/.../agent/GameAgent.kt` - Main agent loop: await input -> build prompt -> run KOOG agent -> send response
- `agent/.../agent/SystemPrompt.kt` - Dynamic system prompt built from available capabilities
- `agent/.../agent/bridge/AgentBridgeImpl.kt` - Agent <-> UI communication via StateFlow + CompletableDeferred
- `agent/.../agent/tools/` - SpriteGeneratorTool, ModelGeneratorTool, MusicGeneratorTool, SoundGeneratorTool, PresentAssetTool
- `agent/.../agent/registry/ToolRegistryFactory.kt` - Conditional tool registration based on configured keys
- `agent/.../agent/strategy/GameAgentStrategy.kt` - `singleRunStrategy()` from KOOG

### GUI
- `gui/.../gui/Main.kt` - Entry point, window setup, `LocalAwtWindow` CompositionLocal
- `gui/.../gui/App.kt` - Root composable, main screen layout, keyboard shortcuts
- `gui/.../gui/viewmodel/AppViewModel.kt` - MVVM state holder, agent lifecycle, workspace management
- `gui/.../gui/components/ChatPanel.kt` - Chat message list + input field
- `gui/.../gui/components/AssetPreview.kt` - The asset panel: routes between agent review, the detail view of one asset, the browser grid and the empty state
- `gui/.../gui/components/AssetBrowser.kt`, `AssetCard.kt` - The folder grid and its cards (selection, drag to folder, context menus)
- `gui/.../gui/thumbs/` - `ThumbnailStore` (thumbnails made off the UI thread, memory + disk caches), `rememberThumbnail`, `ModelThumbnails` (3D model cards)
- `gui/.../gui/viewer3d/` - The 3D model viewer: `SceneModel` (format-neutral model), `GltfLoader` (GLB/glTF via jglTF), `AssimpLoader` (FBX/OBJ), `ScenePose` (animation sampling, joint matrices), `ModelRenderer` (OpenGL 3.3: textures, GPU skinning), `GlContext` (hidden GLFW window + framebuffer), `ViewerSession` (render thread), `ModelViewer` (the composable), `ModelSnapshotter`/`OffscreenRenderer` (stills)
- `gui/.../gui/components/WorkspaceSelector.kt` - Workspace dropdown, new/rename/delete dialogs, context dialog
- `gui/.../gui/theme/Theme.kt` - Material 3 light/dark themes
- `gui/.../gui/util/FolderPicker.kt` - JFileChooser wrapper for native folder selection

## Data Storage Layout

```
~/.gameharness/
  settings.json              # API keys + dark mode preference
  workspace-registry.json    # Paths to all known workspaces
  cache/thumbnails/          # Asset browser thumbnails (WebP, keyed by path + mtime + size; pruned past 512 MB)
  logs/
    gameharness.log          # Rolling log (7 days, 50MB cap)

<any-directory>/             # User-chosen workspace location
  workspace.json             # Workspace metadata (name, path, asset list)
  workspace-instructions.md  # Per-workspace AI instructions (injected into system prompt)
  chat-history.json          # Conversation messages
  assets/
    sprites/                 # 2D sprites (PNG, WebP)
    models/                  # 3D models (GLB; glTF, FBX and OBJ are recognised and viewable too)
    music/                   # Music tracks (MP3)
    sfx/                     # Sound effects (MP3)
```

## How the Agent Works

1. `AppViewModel.startAgent()` creates a `GameAgent` and calls `start(scope)`
2. `GameAgent.start()` creates an OpenRouter executor, initializes only the clients whose API keys are configured, builds a `ToolRegistry` with matching tools, then enters a loop
3. Each iteration: `bridge.awaitUserInput()` blocks until the user sends a message
4. The message is wrapped with conversation history via `buildAgentInput()`
5. Workspace instructions (if any) are appended to the system prompt
6. A fresh `AIAgent` is created with `singleRunStrategy()` and `run(input)` is called
7. KOOG handles LLM calls and tool execution; tools call the API clients and save assets to the workspace
8. The `PresentAssetTool` pauses execution to show the user the result and await approval/denial
9. The agent's text response is sent back via the bridge

## Common Gotchas

- **Kotlin 2.3.0 is mandatory** because KOOG 0.6.3 depends on kotlinx-serialization 1.10.0
- **`jvmToolchain(21)` in every module** - don't use 17
- **`advanceUntilIdle()`** needed in coroutine tests before checking deferred completion
- **`AIAgent` constructor** uses `systemPrompt` not `prompt`
- **`singleRunStrategy()`** imported from `ai.koog.agents.core.agent`
- **`simpleOpenRouterExecutor(apiKey)`** imported from `ai.koog.prompt.executor.llms.all`
- **`OpenRouterModels`** fields: `Claude4_5Sonnet`, `Claude4Sonnet`, etc.
- **Compose click handlers run on AWT EDT** - cannot use `SwingUtilities.invokeAndWait` from click handlers (use `isEventDispatchThread()` check)
- **Nothing in the asset grid reads an asset file on the UI thread.** A composable that calls `ImageIO.read`, `File.exists()` or a model loader runs it for every card on every recomposition; that is what once made a folder of 170 images take two seconds to open. Cards get pictures from `rememberThumbnail`, sizes from `GeneratedAsset.sizeBytes`, and full images from a background decode
- **The 5-second disk check is read-only and off-thread** (`WorkspaceManager.hasExternalChanges`); `syncAssets`, which rewrites `workspace.json`, runs on the UI thread and only when the check says something changed
- **The 3D view is not a component in the window.** It is drawn off screen on its own thread (`ViewerSession`, a hidden GLFW window's OpenGL context) and handed to Compose as an image, so menus and dialogs draw over it and tests can drive it without a window. The frame image must be read from state *inside the draw lambda*: the previous frame is closed as the next arrives, between composition and drawing, and a draw that captured it at composition time crashes in native code
- **OpenGL calls belong to the thread that created the context** — `ModelRenderer`, `RenderTarget` and `ModelSnapshotter` are never touched from the UI thread
- **Workspace paths are absolute** and scattered across filesystem; the registry tracks them
- **API clients are stateful** - must be closed when agent stops to avoid resource leaks
- **Sprite transparent backgrounds use chroma key** - Gemini models struggle with true transparency, so sprites are generated with a solid chroma key background that is automatically removed via flood-fill + edge de-fringing. The chroma key color is auto-selected based on description (green by default, magenta for green-themed sprites, blue if both conflict). Use `--no-bg-removal` (CLI) or `transparentBackground=false` (agent tool) to skip this.

## Test Summary

- **Core**: 186 tests (models, config, workspace, file utils, retry logic, sprite sheet splitting, flood-fill bg removal, multi-pass defringe, trim transparent, replace asset file, open workspace, the read-only change check, key encryption, settings manager)
- **API Clients**: 37 tests (all 4 clients + rate limiter, chroma key prompt/color selection, using Ktor MockEngine)
- **Agent**: 62 tests (agent lifecycle, bridge, system prompt, all tools)
- **CLI**: 49 tests (JSON output, progress reporter, client factory, config/workspace/asset/generate/revise/trim/open commands)
- **GUI**: 20 tests (thumbnail store and its caches, glTF/GLB and OBJ loading, animation sampling and skinning)
- **Total**: 354 tests, all passing

Tests run with `user.home` pointed at an empty `build/test-home` and the key environment variables blanked (root `build.gradle.kts`). Keep it that way: the CLI tests build the real command tree, and with real keys in reach "generate model fails when API key not configured" generates a paid model instead of failing.

What needs a GPU or a screen is not a unit test; it is checked with the tasks under "Measuring and looking at the UI" in the README (`:gui:browserBench`, `:gui:uiShots`, `:gui:viewerBench`, `:gui:windowCheck`).

## CLI Module (`:cli`)

Command-line interface for generating assets without the GUI. Depends only on `:core` and `:api-clients` (no KOOG agent). Outputs structured JSON to stdout.

### Running the CLI

Requires JDK 21 with `JAVA_HOME` set. Use `-q` flag for clean JSON output.

```bash
# Check available generation capabilities
./gradlew :cli:run --args="config" -q

# List workspaces
./gradlew :cli:run --args="workspace list" -q

# Create a workspace
./gradlew :cli:run --args="workspace create -n 'MyGame' -p '/path/to/workspace'" -q

# Open an existing folder as a workspace (auto-discovers assets)
./gradlew :cli:run --args="workspace open -p '/path/to/existing-project' -n 'MyGame'" -q

# Generate a sprite (fast, ~10s)
./gradlew :cli:run --args="generate sprite -w 'MyGame' -d 'a red potion bottle' -s 16bit" -q

# Generate a sprite with image size and dimensions
./gradlew :cli:run --args="generate sprite -w 'MyGame' -d 'a coin icon' -s 8bit --image-size 1K --width 32 --height 32" -q

# Generate a sprite with opaque background (skip green screen removal)
./gradlew :cli:run --args="generate sprite -w 'MyGame' -d 'a grass tile' -s 16bit --no-bg-removal" -q

# Generate a 3D model (slow, 5-10 min)
./gradlew :cli:run --args="generate model -w 'MyGame' -d 'a wooden treasure chest' --art-style low-poly" -q

# Generate a 3D character for rigging: always in a T-pose (see --pose-mode in generate-asset.md)
./gradlew :cli:run --args="generate model -w 'MyGame' -d 'a knight in plate armour, standing in a T-pose with both arms held straight out to the sides' --pose-mode t-pose" -q

# Generate a 3D model in FBX format
./gradlew :cli:run --args="generate model -w 'MyGame' -d 'a sci-fi crate' --format fbx" -q

# Generate a 3D model from an image (image-to-3D)
./gradlew :cli:run --args="generate model -w 'MyGame' -d 'from concept art' --image 'https://example.com/concept.png'" -q

# Generate music (slow, 1-3 min)
./gradlew :cli:run --args="generate music -w 'MyGame' -d 'epic battle theme' --genre orchestral --mood epic" -q

# Generate a sound effect (fast, ~5s)
./gradlew :cli:run --args="generate sfx -w 'MyGame' -d 'coin pickup sound' --duration 1.0" -q

# Revise a sprite (iterate on existing, fast ~10s)
./gradlew :cli:run --args="asset revise -w 'MyGame' -a 'potion_bottle_16bit.png' -d 'make the liquid glow blue'" -q

# Trim transparent borders from a sprite (instant)
./gradlew :cli:run --args="asset trim -w 'MyGame' -a 'potion_bottle_16bit.png'" -q

# List assets
./gradlew :cli:run --args="asset list -w 'MyGame' --type SPRITE" -q
```

### JSON Output Format

All commands output `{"success": true/false, "command": "...", "data": {...}}` or `{"success": false, "command": "...", "error": {"code": "...", "message": "..."}}`.

### Custom Command

Use `/generate-asset` to invoke the asset generation workflow with instructions.
