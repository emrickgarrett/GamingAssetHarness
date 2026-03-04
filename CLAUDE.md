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
| **Meshy** | 3D Models (GLB) | `MESHY_API_KEY` | `api.meshy.ai` (2-stage: preview then refine) |
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
- `gui/.../gui/components/AssetPreview.kt` - Asset display (images rendered, files shown as info cards)
- `gui/.../gui/components/WorkspaceSelector.kt` - Workspace dropdown, new/rename/delete dialogs, context dialog
- `gui/.../gui/theme/Theme.kt` - Material 3 light/dark themes
- `gui/.../gui/util/FolderPicker.kt` - JFileChooser wrapper for native folder selection

## Data Storage Layout

```
~/.gameharness/
  settings.json              # API keys + dark mode preference
  workspace-registry.json    # Paths to all known workspaces
  logs/
    gameharness.log          # Rolling log (7 days, 50MB cap)

<any-directory>/             # User-chosen workspace location
  workspace.json             # Workspace metadata (name, path, asset list)
  workspace-instructions.md  # Per-workspace AI instructions (injected into system prompt)
  chat-history.json          # Conversation messages
  assets/
    sprites/                 # 2D sprites (PNG, WebP)
    models/                  # 3D models (GLB)
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
- **Workspace paths are absolute** and scattered across filesystem; the registry tracks them
- **API clients are stateful** - must be closed when agent stops to avoid resource leaks

## Test Summary

- **Core**: 70 tests (models, config, workspace, file utils, retry logic)
- **API Clients**: 24 tests (all 4 clients + rate limiter, using Ktor MockEngine)
- **Agent**: 47 tests (agent lifecycle, bridge, system prompt, all tools)
- **Total**: 141 tests, all passing
