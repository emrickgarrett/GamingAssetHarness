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
- Aspect ratios (--aspect-ratio): `1:1` (default), `2:3`, `3:2`, `4:3`, `16:9`
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
- If the user wants changes, generate again with an adjusted description

## Listing workspace assets
```bash
./gradlew :cli:run --args="asset list -w '<workspace-name>'" -q
```
Filter options: `--type SPRITE|MODEL_3D|MUSIC|SOUND_EFFECT`, `--status PENDING|APPROVED|DENIED`

$ARGUMENTS
