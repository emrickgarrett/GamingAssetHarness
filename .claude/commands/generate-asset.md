---
description: Generate a game asset (sprite, 3D model, music, sound effect) using the GameDeveloperHarness CLI
---

# Generate Game Asset

Generate game assets using the GameDeveloperHarness CLI. All commands output JSON to stdout.

## Prerequisites

- JDK 21 must be installed and `JAVA_HOME` must be set (or discoverable)
- API keys must be configured via environment variables or `~/.gameharness/settings.json`
- This command must be run from the GameDeveloperHarness repo root (where `gradlew` lives)

**IMPORTANT**: If `JAVA_HOME` is not already set in the environment, find the JDK 21 path first:
- Check common locations: `$JAVA_HOME`, `/usr/lib/jvm/java-21*`, or IDE-bundled JDKs
- On the current machine, check `JAVA_HOME` with `echo $JAVA_HOME`
- If not set, find it and export it before running commands

All commands use `-q` to suppress Gradle output so only JSON hits stdout:
```
./gradlew :cli:run --args="<command>" -q
```

## Step 1: Check capabilities

First check which asset types can be generated:
```bash
./gradlew :cli:run --args="config" -q
```

## Step 2: Find or create a workspace

List existing workspaces:
```bash
./gradlew :cli:run --args="workspace list" -q
```

Create a workspace if needed. **IMPORTANT: Always use an absolute path for `-p`** (e.g., `/home/user/my-game-workspace`, not `./workspaces/foo`). Relative paths cause files to resolve incorrectly when the CLI runs from its module subdirectory:
```bash
./gradlew :cli:run --args="workspace create -n '<name>' -p '<absolute-path>'" -q
```

When choosing an existing workspace, check the `directoryPath` in the workspace list response. If it's a relative path (starts with `.`), prefer creating a new workspace with an absolute path instead, to avoid file resolution issues.

## Step 3: Generate the asset

Based on the user's request, determine the asset type and run the appropriate command.

### 2D Sprite (fast, ~10s)
```bash
./gradlew :cli:run --args="generate sprite -w '<workspace-name>' -d '<detailed description>' -s '<style>'" -q
```
- Styles: `8bit`, `16bit` (default), `modern`, `realistic`
- Aspect ratios (--aspect-ratio): `1:1` (default), `2:3`, `3:2`, `3:4`, `4:3`, `4:5`, `5:4`, `9:16`, `16:9`, `21:9`
- Image size presets (--image-size): `512`, `1K`, `2K`, `4K` (optional, controls resolution tier)
- Dimension hints (--width, --height): pixel values like `32`, `64`, `128`, `256` (hints in prompt, not exact)
- Background removal (automatic by default):
  - Sprites are generated with a chroma key colored background, then the background is automatically removed to produce clean transparency
  - The chroma key color is auto-selected based on the description: **green** (#00b140) by default, **magenta** (#ff00ff) for green-themed sprites (trees, slimes, grass, etc.), **blue** (#0000ff) if both green and purple/pink conflict
  - Removal pipeline: BFS flood-fill from image borders (tolerance 60) → 2 passes of edge de-fringing (tolerance 120) to clean anti-aliased edges
  - Output is always PNG (to preserve alpha channel)
  - Add `--no-bg-removal` to skip this entirely and keep the opaque background (useful for tiles, backgrounds, or when transparency isn't needed)
  - If the generated sprite has unwanted fringe artifacts, try regenerating — results vary per generation
- Reference images: add `-r '<path>'` (can repeat for multiple)
- Folder: add `-f '<folder>'` to organize into a subfolder

### 3D Model (slow, ~5-10min)
```bash
./gradlew :cli:run --args="generate model -w '<workspace-name>' -d '<detailed description>' --art-style '<style>'" -q
```
- Styles: `realistic` (default), `cartoon`, `low-poly`, `sculpture`

### Music (slow, ~1-3min)
```bash
./gradlew :cli:run --args="generate music -w '<workspace-name>' -d '<detailed description>' --genre '<genre>' --mood '<mood>'" -q
```
- Genre examples: `orchestral`, `electronic`, `chiptune`, `ambient`, `rock`
- Mood examples: `epic`, `calm`, `tense`, `cheerful`, `mysterious`

### Sound Effect (fast, ~5s)
```bash
./gradlew :cli:run --args="generate sfx -w '<workspace-name>' -d '<detailed description>' --duration <seconds>" -q
```
- Duration: 0.5 to 22.0 seconds (default 2.0)

## Step 4: Use the generated file

Parse the JSON response. On success, `data.filePath` has the absolute path to the generated file.

- For sprites (PNG), use the Read tool to view the image and show the user
- Copy or reference the file in the current project as needed
- If the user wants changes to a sprite, use `asset revise` (see Step 5)

## Step 5: Revise an existing sprite

If the user wants changes to a previously generated sprite, use `asset revise` instead of generating from scratch. This automatically uses the original sprite as a reference image so the AI can see what it's revising, and inherits style/dimensions from the original.

```bash
./gradlew :cli:run --args="asset revise -w '<workspace-name>' -a '<asset-filename>' -d '<revision instructions>'" -q
```

- `-a` / `--asset`: The filename of the sprite to revise (from `asset list` output). Supports partial matching, but use exact filename to avoid ambiguity.
- `-d` / `--description`: What to change (e.g., "make the sword glow blue", "add a shield", "change to darker tones")
- Style, aspect ratio, dimensions, folder, and background removal settings are automatically inherited from the original. Override with `-s`, `--aspect-ratio`, `--width`, `--height`, `--image-size`, `--no-bg-removal`, `-f` if needed.
- Only sprites can be revised (other asset types will return an error).
- The JSON response includes `originalFileName` so you can track provenance.

### Iteration workflow

1. Generate the initial sprite with `generate sprite`
2. Show the user the result (use the Read tool on the `filePath` from the JSON response)
3. If the user requests changes, use `asset revise` with the filename and revision instructions
4. Repeat steps 2-3 until the user is satisfied
5. To list all sprites: `asset list -w '<workspace-name>' --type SPRITE`

## Listing workspace assets
```bash
./gradlew :cli:run --args="asset list -w '<workspace-name>'" -q
```
Filter options: `--type SPRITE|MODEL_3D|MUSIC|SOUND_EFFECT`, `--status PENDING|APPROVED|DENIED`

## Tips for Best Results

### Sprite descriptions
- Be specific about the subject — "a medieval iron sword with a leather-wrapped hilt" works better than "a sword"
- Include art style context in the description too — "pixel art style fire elemental" helps the model
- For characters, specify pose: "idle stance facing right", "walking animation frame 1"

### Transparent background tips
- The default chroma key pipeline works well for most sprites (characters, items, UI elements)
- Use `--no-bg-removal` for **tiles**, **backgrounds**, **terrain**, or anything where you need the full image without transparency
- The system auto-detects green-themed sprites (trees, slimes, etc.) and switches to magenta chroma key to avoid conflicts
- If you see residual fringe on a generated sprite, regenerate — quality varies per generation
- For **tilesets or sprite sheets**, generate individual sprites separately; the background removal is designed for single-subject images

$ARGUMENTS
