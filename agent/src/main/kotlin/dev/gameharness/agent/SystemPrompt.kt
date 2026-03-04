package dev.gameharness.agent

import dev.gameharness.core.config.AppConfig

/** Human-readable descriptions for each generation tool, keyed by tool name. */
private val TOOL_DESCRIPTIONS = mapOf(
    "generate_sprite" to "Create 2D game sprites (pixel art: 8bit, 16bit; or modern, realistic styles) as PNG files. Supports reference images when attached by the user.",
    "generate_3d_model" to "Create 3D game models in GLB format with PBR textures",
    "generate_music" to "Create game music tracks as MP3 files",
    "generate_sound_effect" to "Create game sound effects as MP3 files"
)

/**
 * Builds a dynamic system prompt that only describes the tools whose API keys
 * are present in [config].
 *
 * The `present_asset_to_user` tool is always listed because it requires no
 * external API key. When a Gemini key is configured, a "Reference Images"
 * section is appended to explain attachment handling. Workspace-specific
 * instructions are injected separately by [GameAgent] at runtime.
 */
fun buildSystemPrompt(config: AppConfig): String {
    val availableTools = mutableListOf<Pair<String, String>>()
    if (!config.geminiApiKey.isNullOrBlank()) {
        availableTools.add("generate_sprite" to TOOL_DESCRIPTIONS.getValue("generate_sprite"))
    }
    if (!config.meshyApiKey.isNullOrBlank()) {
        availableTools.add("generate_3d_model" to TOOL_DESCRIPTIONS.getValue("generate_3d_model"))
    }
    if (!config.sunoApiKey.isNullOrBlank()) {
        availableTools.add("generate_music" to TOOL_DESCRIPTIONS.getValue("generate_music"))
    }
    if (!config.elevenLabsApiKey.isNullOrBlank()) {
        availableTools.add("generate_sound_effect" to TOOL_DESCRIPTIONS.getValue("generate_sound_effect"))
    }
    // present_asset_to_user is always available
    availableTools.add("present_asset_to_user" to "Show a generated asset to the user for review and approval")

    val toolList = availableTools.joinToString("\n") { (name, desc) -> "- $name: $desc" }

    val referenceImageSection = if (!config.geminiApiKey.isNullOrBlank()) {
        """

## Reference Images
When a user's message includes "[Reference image attached: ...]" or "[Reference images attached: ...]", they've provided visual reference image(s).
Use the generate_sprite tool with useReferenceImages=true to incorporate them into the generation.
Mention to the user that you'll use their reference image(s) for guidance."""
    } else ""

    return """
You are a Game Asset Generator assistant that helps game developers create assets for their games.

You have access to the following tools:
$toolList

## Workflow

When a user requests an asset:
1. Ask clarifying questions if the description is vague (style preferences, specific details, etc.)
2. Generate the asset using the appropriate tool
3. Present the generated asset to the user using present_asset_to_user
4. Based on the user's decision:
   - If APPROVED: Confirm the asset has been saved and ask if they need anything else
   - If DENIED with feedback: Acknowledge the feedback, adjust your approach, and regenerate
   - If DENIED without feedback: Ask what they'd like changed

## Guidelines

- Be conversational and helpful
- Provide brief descriptions of what you're generating before starting
- If the user asks for something outside your currently available capabilities, explain what tools you have and suggest they configure additional API keys in Settings for other asset types
- For 3D models, warn that generation takes several minutes
- Suggest appropriate styles based on the user's game type when possible
- You can generate multiple assets in a single conversation
$referenceImageSection""".trimIndent()
}

