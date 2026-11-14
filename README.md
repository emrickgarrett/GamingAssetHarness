# Game Developer Harness

An AI-powered desktop application for generating game assets through natural language chat. Describe what you need — sprites, 3D models, music, or sound effects — and the AI agent handles the rest.

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-purple)
![Compose](https://img.shields.io/badge/Compose_Desktop-1.8.2-blue)
![License](https://img.shields.io/badge/License-MIT-green)

## What It Does

Game Developer Harness acts as an intelligent assistant for game asset creation. You chat with an AI agent that understands your creative intent, calls the appropriate generation APIs, and saves the results directly to your project workspace.

**Supported asset types:**

| Asset | Format | Powered By |
|-------|--------|------------|
| 2D Sprites & Textures | PNG, WebP | NanoBanana (Google Gemini) |
| 3D Models | GLB, FBX, OBJ | Meshy |
| Music Tracks | MP3 | Suno |
| Sound Effects | MP3 | ElevenLabs |

The agent adapts to your setup — only the services you configure will be available. At minimum, you need an OpenRouter API key for the AI reasoning layer.

## Features

- **Chat-based workflow** — describe assets in natural language, review and approve/deny results
- **IDE-style workspaces** — create workspaces in any folder on your filesystem; all generated assets are saved there
- **Workspace instructions** — set persistent per-workspace context (art style, format preferences, project guidelines) that the AI follows across all sessions
- **Automatic transparent backgrounds** — sprites are generated with a chroma key background that is automatically removed via flood-fill and multi-pass edge de-fringing to produce clean transparency. The chroma key color is intelligently selected based on sprite content (green by default, magenta for nature/green sprites, blue when both conflict)
- **NanoBanana model selection** — choose between Nano Banana, Nano Banana 2, or Nano Banana Pro for 2D generation
- **Image-to-3D conversion** — turn 2D concept art into 3D models by providing an image URL
- **3D format selection** — export 3D models as GLB, FBX, or OBJ
- **3D model viewer** — open a GLB, glTF, FBX or OBJ model in place and orbit, pan and zoom around it, with its textures. Rigged models play their animation clips: pick a clip, pause, scrub, change speed. Bones, wireframe and untextured views are a click away
- **An asset browser that keeps up** — thumbnails of sprites and 3D models are made in the background and cached, so a folder of hundreds of assets opens at once and scrolls smoothly
- **Multi-service integration** — generate 2D, 3D, music, and audio from a single interface
- **Progress tracking** — real-time progress for long-running generations (3D models, music)
- **Asset management** — approve, deny (with feedback), or delete generated assets
- **Light & dark themes** — Material 3 theming with persistent preference
- **Open existing folders** — use any existing project directory as a workspace; existing assets in `assets/` subdirectories are auto-discovered
- **Keyboard shortcuts** — `Ctrl+,` for settings, `Ctrl+Shift+N` for new workspace, `Ctrl+Shift+O` to open existing folder
- **Cross-platform** — runs on Windows, macOS, and Linux (the 3D model viewer and model thumbnails need Windows or Linux; see [Troubleshooting](#troubleshooting))

## Prerequisites

- **JDK 21** or later
- **API Keys** (see [API Keys](#api-keys) below)
- *Optional, only for procedural 3D models:* **Python 3.10+** and **Node 18+**
  (see [Procedural 3D models](#procedural-3d-models-img2threejs))

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/yourusername/GameDeveloperHarness.git
cd GameDeveloperHarness
```

### 2. Set up your JDK

Make sure `JAVA_HOME` points to a JDK 21+ installation:

```bash
# Example for various JDK locations:
export JAVA_HOME="/path/to/jdk-21"

# Verify
$JAVA_HOME/bin/java -version
```

### 3. Build the project

```bash
./gradlew build
```

This compiles all modules and runs the full test suite (354 tests). The tests never use your API keys or your `~/.gameharness` folder: each test run gets an empty home directory of its own.

### 4. Run the application

```bash
./gradlew :gui:run
```

On first launch, you'll be prompted to enter your API keys in the Settings dialog.

## API Keys

### Required

| Service | Purpose | Get a Key |
|---------|---------|-----------|
| **OpenRouter** | AI agent reasoning (LLM) | [openrouter.ai](https://openrouter.ai/) |

### Optional (enable asset types)

| Service | Asset Type | Get a Key |
|---------|-----------|-----------|
| **Google Gemini** (NanoBanana) | 2D Sprites & Textures | [ai.google.dev](https://ai.google.dev/) |
| **Meshy** | 3D Models | [meshy.ai](https://www.meshy.ai/) |
| **Suno** | Music | [suno.com](https://suno.com/) |
| **ElevenLabs** | Sound Effects | [elevenlabs.io](https://elevenlabs.io/) |

You can configure keys in two ways:

**Option A: In-app Settings dialog** (recommended)
- Press `Ctrl+,` or click the gear icon
- Keys are saved to `~/.gameharness/settings.json`

**Option B: Environment variables**
```bash
export OPENROUTER_API_KEY="your-key-here"
export GEMINI_API_KEY="your-key-here"
export MESHY_API_KEY="your-key-here"
export SUNO_API_KEY="your-key-here"
export ELEVENLABS_API_KEY="your-key-here"
```

You can also copy `.env.example` to `.env` and fill in your keys. Environment variables take precedence over saved settings.

### NanoBanana Model Selection

When a Gemini API key is configured, the Settings dialog shows a model dropdown for 2D sprite generation:

| Model | ID | Best For |
|-------|----|----------|
| **Nano Banana** | `gemini-2.5-flash-image` | Fast generation, good for iteration |
| **Nano Banana 2** | `gemini-3.1-flash-image-preview` | Higher fidelity, latest model |
| **Nano Banana Pro** | `gemini-3-pro-image-preview` | Up to 4K, best quality for final assets |

All three models use the same Gemini API key. The model selection is saved per-user and takes effect immediately.

## Usage

### Creating a Workspace

1. Click **+ New** in the workspace dropdown (or press `Ctrl+Shift+N`)
2. Enter a name for your workspace (e.g., "My RPG Project")
3. Click **Browse** to choose a folder anywhere on your filesystem
4. Click **Create**

All generated assets will be saved in your chosen folder under an `assets/` subdirectory, organized by type (`sprites/`, `models/`, `music/`, `sfx/`).

### Opening an Existing Folder

Already have a game project? Click **Open** in the workspace dropdown (or press `Ctrl+Shift+O`) to use any existing directory as a workspace. The app will:

- Initialize workspace metadata alongside your existing files (nothing is moved or deleted)
- Auto-discover any assets already present in `assets/sprites/`, `assets/models/`, `assets/music/`, or `assets/sfx/`
- If the folder already contains a `workspace.json` from a previous session, it will be imported automatically

### Workspace Instructions

Click the notepad icon next to the workspace selector to set persistent instructions for the workspace. These are included with every message to the AI agent, so you can specify:

- Art style preferences ("16-bit pixel art, 64x64 resolution")
- Color palette guidelines ("dark fantasy palette, muted tones")
- Format requirements ("all sprites should have transparent backgrounds")
- Project-specific context ("this is a sci-fi platformer set on Mars")

Instructions are saved as `workspace-instructions.md` in your workspace folder and persist across sessions.

### Generating Assets

Chat naturally with the agent:

> "Generate a sword sprite in 16-bit pixel art style"

> "Create background music for a boss fight — epic orchestral, 30 seconds"

> "Make a 3D model of a treasure chest in a low-poly cartoon style"

> "Generate a laser blast sound effect, about 1 second long"

The agent will:
1. Interpret your request and ask clarifying questions if needed
2. Call the appropriate generation API
3. Show you the result with a preview
4. Wait for your approval or denial (with optional feedback)

### Reviewing Assets

When an asset is generated:
- **Approve** to keep it in your workspace
- **Deny** to reject it (optionally provide feedback so the agent can try again)

### Browsing Assets

The panel on the right lists the workspace's assets by folder. Each card shows a thumbnail: the sprite itself, or the 3D model drawn from the front quarter (a rigged model is posed partway through its first clip, so an idle, a run and a fall of the same character can be told apart). Thumbnails are made in the background and kept in `~/.gameharness/cache/thumbnails`, so a folder is only ever slow to fill in the first time it is seen, and never slow to open.

Click a card to open the asset; **Back to Assets** returns to the folder where you left it, scrolled to the same place. Ctrl+click and Shift+click select several cards, right-click offers move and delete, and cards can be dragged onto a folder.

### Viewing 3D Models

Clicking a 3D model opens it in the viewer. GLB, glTF, FBX and OBJ files are supported, with their textures (embedded in the file, or beside it for OBJ and FBX).

| To | Do |
|----|----|
| Orbit | Drag |
| Pan | Right-drag, middle-drag, or Shift+drag |
| Zoom | Scroll |
| Frame the model again | **Reset View** |

The toolbar switches **Textures** (off shows the bare shape in grey), **Wireframe**, **Bones** (the skeleton, drawn over a rigged model), the floor **Grid**, and the **Materials** panel, which lists each material and highlights it on the model when clicked.

A model with animation starts playing its first clip. The bar under the view picks the clip (or the rest pose), pauses, scrubs along the timeline and cycles the speed (1×, 2×, ¼×, ½×).

## Claude Code Skill (CLI)

GameDeveloperHarness includes a CLI module and a Claude Code skill that lets Claude generate game assets for you from **any project**. When you're building a game in a separate repo, just invoke `/generate-asset` and Claude will handle workspace setup, asset generation, and file delivery.

### Installation

**1. Set environment variables**

Add these to your shell profile (`~/.bashrc`, `~/.zshrc`, etc.):

```bash
export JAVA_HOME="/path/to/jdk-21"
export GAME_HARNESS_HOME="/path/to/GameDeveloperHarness"
```

**2. Build the CLI module**

```bash
cd "$GAME_HARNESS_HOME" && ./gradlew :cli:build
```

**3. Install the Claude Code skill**

Copy the skill file to your global Claude Code commands directory:

```bash
mkdir -p ~/.claude/commands
cp "$GAME_HARNESS_HOME/.claude/commands/generate-asset.md" ~/.claude/commands/generate-asset.md
```

Then update the copied file to use `$GAME_HARNESS_HOME` instead of relative paths. The repo includes a ready-to-use global version — see the command file for details.

**4. Use it**

From any Claude Code session, in any project:

```
/generate-asset create a 16-bit treasure chest sprite
```

Claude will check your configured API keys, find or create a workspace, generate the asset, and show you the result.

### How It Works

The CLI module (`:cli`) depends only on `:core` and `:api-clients` — no KOOG agent or GUI required. It accepts commands via `./gradlew :cli:run --args="..."` and outputs structured JSON to stdout.

Available commands:
- `config` — show which asset types are available based on configured API keys
- `workspace list` / `workspace create` / `workspace open` — manage workspaces
- `generate sprite|model|music|sfx` — generate assets
- `asset list` — list assets in a workspace
- `asset revise` — revise an existing sprite using it as a reference image
- `asset trim` — trim transparent borders from a sprite to reduce file size

All generated assets are auto-approved and saved to the workspace. If you don't like a result, use `asset revise` to iterate on sprites with the original as a visual reference, or tell Claude to regenerate with an adjusted description. Use `asset trim` to crop transparent borders from sprites after background removal.

## Project Structure

```
GameDeveloperHarness/
  core/               # Data models, config, workspace management
  api-clients/        # HTTP clients for Gemini, Meshy, Suno, ElevenLabs
  agent/              # KOOG AI agent, tools, system prompt, UI bridge
  gui/                # Compose Desktop UI, themes, viewmodel
  cli/                # CLI interface for Claude Code integration
  .claude/commands/   # Claude Code skill definitions
  gradle/             # Version catalog (libs.versions.toml)
```

### Module Dependencies

```
:core  -->  :api-clients  -->  :agent  -->  :gui
                          \-->  :cli
```

The GUI and CLI modules are independent — both depend on `:core` and `:api-clients`, but neither depends on the other.

## Building Native Installers

```bash
# Windows (MSI)
./gradlew :gui:packageMsi

# Linux (DEB)
./gradlew :gui:packageDeb
```

Installers are output to `gui/build/compose/binaries/`.

## Configuration Files

The application stores its data in your home directory:

```
~/.gameharness/
  settings.json              # API keys and preferences
  workspace-registry.json    # Index of all workspace locations
  cache/
    thumbnails/              # Asset browser thumbnails (safe to delete; rebuilt as needed)
  logs/
    gameharness.log          # Application logs (7-day rolling)
```

Each workspace stores its own data:

```
your-workspace-folder/
  workspace.json             # Workspace metadata
  workspace-instructions.md  # Persistent AI instructions
  chat-history.json          # Conversation history
  assets/
    sprites/                 # PNG, WebP files
    models/                  # GLB files (glTF, FBX and OBJ are recognised too)
    music/                   # MP3 files
    sfx/                     # MP3 files
```

## Development

### Running Tests

```bash
# All tests (354 across all modules)
./gradlew test

# Specific module
./gradlew :core:test
./gradlew :api-clients:test
./gradlew :agent:test
./gradlew :cli:test
./gradlew :gui:test
```

### Measuring and looking at the UI

Four Gradle tasks exercise the asset browser and the 3D viewer against real files. The first three draw the app's own asset panel with no window on screen; all of them only read the workspace they are pointed at.

```bash
# Time folder navigation: first frame of every folder, a scroll to its end, the way back
./gradlew :gui:browserBench --args="<workspace.json> <out-dir> 1400 1300"

# Screenshot the panel while driving it: open a folder, click a model, orbit it
./gradlew :gui:uiShots --args="<workspace.json> <out-dir> 1300 1000 folder:rigs settle shot:rigs click:410,150 wait:2500 shot:viewer"

# Time the 3D viewer: opening a model, frames per second while turning it and while it animates
./gradlew :gui:viewerBench --args="1400 1200 <model.glb> <model.fbx>"

# Run the whole app in a real window with synthetic mouse input, against a throwaway home directory
./gradlew :gui:windowCheck -PcheckHome=<dir> --args="<out-dir> 1300 900 wait:2000 shot:start"
```

`UiShots.kt` and `WindowCheck.kt` list the actions. `windowCheck` is the only one that opens a window (it stays on top and takes no focus), and it refuses to run without `-PcheckHome`, so it never touches your own settings.

### Tech Stack

- **Kotlin 2.3.0** with coroutines and serialization
- **KOOG 0.6.3** — JetBrains AI agent framework
- **Compose Desktop 1.8.2** — Material 3 UI
- **Ktor 3.1.3** — Async HTTP client
- **LWJGL 3.3.6** (OpenGL, GLFW, Assimp) and **jglTF 2.0.4** — the 3D model viewer
- **JUnit 5** — Testing framework
- **SLF4J + Logback** — Logging

## Troubleshooting

**Build fails with serialization errors**
Make sure you're using Kotlin 2.3.0. KOOG 0.6.3 requires kotlinx-serialization 1.10.0, which needs Kotlin 2.3.0+.

**"No AWT Window provided" error**
This should not occur in normal usage. It means a composable is trying to access the native window reference outside the main window scope.

**JFileChooser doesn't appear / "Cannot call invokeAndWait from EDT" error**
This was fixed — the folder picker detects if it's already on the AWT Event Dispatch Thread and runs directly instead of dispatching.

**Agent doesn't respond**
Check that your OpenRouter API key is valid (use the Test button in Settings). Check `~/.gameharness/logs/gameharness.log` for detailed error information.

**Asset type not available**
The agent only offers asset types for which you've configured API keys. Check Settings to make sure the relevant key is entered.

**"The 3D view could not start"**
The model viewer and the model thumbnails draw with OpenGL 3.3 in a hidden window of their own. They need a graphics driver that offers it and a desktop session; on a remote session without GPU access, or on macOS (where that hidden window cannot share the process with the app's own window), the viewer shows this message and model cards keep their icon. Everything else works as usual.

**A thumbnail looks out of date**
Thumbnails are keyed by the file's path, size and modification time, so this should not happen; if it does, delete `~/.gameharness/cache/thumbnails` and they are made again.

## License

MIT

## Procedural 3D models (img2threejs)

A second route to 3D, alongside Meshy. Where Meshy generates a **mesh** from an
image, [img2threejs](https://github.com/img2threejs/img2threejs) (Apache-2.0)
rebuilds the object as **procedural Three.js code** — animation-ready and
editable, rather than a baked surface.

It is not an API provider, and deliberately does not sit under `generate`.
It is an agent-driven pipeline of eight gated passes
(blockout → structural → form → material → surface → lighting → interaction →
optimization). Python scripts enforce the gates and own pipeline state; a
vision-capable agent authors the sculpt spec and scores each
reference-vs-render comparison sheet. Its own docs put it as *"scripts enforce,
the model judges."*

### Setup

```sh
git clone https://github.com/img2threejs/img2threejs third_party/img2threejs
(cd tools/threejs-bake && npm install)   # three + tsx, for the GLB bake
```

The checkout is resolved from `--forge`, then `IMG2THREEJS_PATH`, then by
walking up for `third_party/img2threejs`. Both directories are gitignored:
the checkout is a pinned upstream tool, not our source.

### Commands

| Command | Purpose |
|---|---|
| `sculpt init -w <ws> -i <image> -n <name>` | Validate the reference, scaffold a session in the workspace |
| `sculpt next -s <spec>` | Current pass, exact next command, unmet acceptance criteria |
| `sculpt run <script> [args]` | Run any forge script (usually whatever `next` reported) |
| `sculpt bake -w <ws> -s <spec> --factory <ts>` | Bake the factory to `.glb` and register it as an asset |
| `sculpt render -m <glb> -o <dir>` | Screenshot the model from several angles for a pass's review gate |

### Why bake to GLB

The pipeline emits TypeScript, which the asset viewer cannot render and other
engines cannot load. `sculpt bake` runs the factory headlessly and exports a
`.glb`, so the factory stays the editable master while the mesh is portable.
The result is registered as an ordinary `MODEL_3D` asset — the existing
viewer and every asset command work on it with no special-casing, and
provenance (spec, factory, provider) is recorded in `generationParams`.

### Rendering for the review gates

Passes 2-8 each gate on a **rendered screenshot** compared against the reference
image, and the forge scripts deliberately ship no renderer (stdlib-only Python).
`sculpt render` fills that in using the harness's own GLB viewer, so a review
shot and the asset window show the same thing:

```bash
harness sculpt render -m assets/models/sculpt/rum-barrel-sculpt.glb -o sculpts/rum-barrel/review
```

By default it shoots four angles (three-quarter, front, side, top) into the
output directory; pass `--angles 35:20` for a single named file, or any
comma-separated `azimuth:elevation` list. `--size` sets the square resolution.

Under the hood this runs `dev.gameharness.gui.viewer3d.OffscreenRenderer`: a
hidden GLFW window owning an OpenGL 3.3 context, drawing into an off-screen
framebuffer. It needs a display server, so it works on a desktop but not on a
bare headless CI box (that would want EGL/OSMesa).

The renderer lives in `:gui` but the CLI does not depend on that module — the
CLI is deliberately lean, and pulling in Compose Desktop for a screenshot would
be a poor trade. Instead the gui build publishes its runtime classpath
(`:gui:renderClasspath` → `gui/build/renderer-classpath.txt`) and `sculpt render`
spawns a plain JVM against it. Any gui build refreshes that file; if it is
missing, `sculpt render` says so and names the task to run.
