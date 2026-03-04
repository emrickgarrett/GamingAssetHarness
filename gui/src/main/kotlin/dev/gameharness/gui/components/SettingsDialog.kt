package dev.gameharness.gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.gameharness.core.config.SavedSettings
import dev.gameharness.gui.BuildInfo
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import kotlinx.coroutines.launch

/**
 * Modal dialog for configuring API keys, selecting the NanoBanana model, toggling dark mode,
 * and viewing application info. On first run it is non-dismissible until the required OpenRouter key is set.
 */
@Composable
fun SettingsDialog(
    initialSettings: SavedSettings,
    isFirstRun: Boolean,
    onSave: (SavedSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var openRouterKey by remember { mutableStateOf(initialSettings.openRouterApiKey) }
    var geminiKey by remember { mutableStateOf(initialSettings.geminiApiKey) }
    var meshyKey by remember { mutableStateOf(initialSettings.meshyApiKey) }
    var sunoKey by remember { mutableStateOf(initialSettings.sunoApiKey) }
    var elevenLabsKey by remember { mutableStateOf(initialSettings.elevenLabsApiKey) }
    var nanoBananaModel by remember { mutableStateOf(initialSettings.nanoBananaModel) }
    var darkMode by remember { mutableStateOf(initialSettings.darkMode) }
    var showKeys by remember { mutableStateOf(false) }

    var validationStatus by remember { mutableStateOf<String?>(null) }
    var isValidating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canSave = openRouterKey.isNotBlank()
    val visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = { if (!isFirstRun) onDismiss() },
        modifier = modifier.widthIn(min = 500.dp),
        title = {
            Text(
                text = if (isFirstRun) "Welcome! Set Up Your API Keys" else "API Key Settings",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isFirstRun) {
                    Text(
                        text = "Enter your OpenRouter API key to get started. " +
                            "Other keys are optional — add them later to unlock more asset types. " +
                            "Keys are stored locally on your machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Required key with validation
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ApiKeyField(
                        label = "OpenRouter API Key *",
                        value = openRouterKey,
                        onValueChange = {
                            openRouterKey = it
                            validationStatus = null
                        },
                        visualTransformation = visualTransformation,
                        isRequired = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            isValidating = true
                            validationStatus = null
                            scope.launch {
                                validationStatus = testOpenRouterKey(openRouterKey.trim())
                                isValidating = false
                            }
                        },
                        enabled = openRouterKey.isNotBlank() && !isValidating,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Test", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                validationStatus?.let { status ->
                    val isValid = status == "valid"
                    Text(
                        text = if (isValid) "API key is valid" else "Invalid: $status",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isValid) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                Text(
                    text = "Optional — configure to unlock asset generation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ApiKeyField(
                    label = "Gemini API Key (2D Sprites)",
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    visualTransformation = visualTransformation,
                    isRequired = false
                )
                NanoBananaModelSelector(
                    selectedModel = nanoBananaModel,
                    onModelSelected = { nanoBananaModel = it },
                    enabled = geminiKey.isNotBlank()
                )
                ApiKeyField(
                    label = "Meshy API Key (3D Models)",
                    value = meshyKey,
                    onValueChange = { meshyKey = it },
                    visualTransformation = visualTransformation,
                    isRequired = false
                )
                ApiKeyField(
                    label = "Suno API Key (Music)",
                    value = sunoKey,
                    onValueChange = { sunoKey = it },
                    visualTransformation = visualTransformation,
                    isRequired = false
                )
                ApiKeyField(
                    label = "ElevenLabs API Key (Sound Effects)",
                    value = elevenLabsKey,
                    onValueChange = { elevenLabsKey = it },
                    visualTransformation = visualTransformation,
                    isRequired = false
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showKeys, onCheckedChange = { showKeys = it })
                    Text("Show keys", style = MaterialTheme.typography.bodySmall)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = darkMode, onCheckedChange = { darkMode = it })
                    Text("Dark mode", style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                // About section
                Text(
                    text = "About",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Game Developer Harness v${BuildInfo.version}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "AI-powered game asset generator. " +
                        "Built with KOOG 0.6.3, Compose Desktop, and OpenRouter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        SavedSettings(
                            openRouterApiKey = openRouterKey.trim(),
                            geminiApiKey = geminiKey.trim(),
                            meshyApiKey = meshyKey.trim(),
                            sunoApiKey = sunoKey.trim(),
                            elevenLabsApiKey = elevenLabsKey.trim(),
                            nanoBananaModel = nanoBananaModel,
                            darkMode = darkMode
                        )
                    )
                },
                enabled = canSave
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            if (!isFirstRun) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation,
    isRequired: Boolean,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = visualTransformation,
        isError = isRequired && value.isBlank(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
    )
}

private data class NanoBananaModelOption(
    val label: String,
    val modelId: String,
    val description: String
)

private val nanoBananaModels = listOf(
    NanoBananaModelOption("Nano Banana", "gemini-2.5-flash-image", "Original — fast, efficient"),
    NanoBananaModelOption("Nano Banana 2", "gemini-3.1-flash-image-preview", "Latest — higher fidelity"),
    NanoBananaModelOption("Nano Banana Pro", "gemini-3-pro-image-preview", "Pro — up to 4K, best quality")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NanoBananaModelSelector(
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = nanoBananaModels.find { it.modelId == selectedModel } ?: nanoBananaModels.first()

    Column {
        Text(
            text = "NanoBanana Model",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it }
        ) {
            OutlinedTextField(
                value = selectedOption.label,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                nanoBananaModels.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onModelSelected(option.modelId)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private suspend fun testOpenRouterKey(apiKey: String): String {
    return try {
        val client = HttpClient(CIO)
        val response = client.get("https://openrouter.ai/api/v1/models") {
            header("Authorization", "Bearer $apiKey")
        }
        client.close()
        if (response.status.value == 200) "valid"
        else if (response.status.value == 401) "Invalid API key"
        else "HTTP ${response.status.value}"
    } catch (e: Exception) {
        "Connection error: ${e.message?.take(50)}"
    }
}
