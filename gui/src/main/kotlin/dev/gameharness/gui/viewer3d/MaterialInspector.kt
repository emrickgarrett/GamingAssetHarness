package dev.gameharness.gui.viewer3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Side panel displaying material properties for a loaded 3D model.
 *
 * Shows each material's base color, metallic/roughness values, and emissive color.
 * Clicking a material highlights the corresponding meshes in the viewport.
 */
@Composable
fun MaterialInspectorPanel(
    materials: List<GlbMaterial>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.width(220.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Materials (${materials.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(4.dp)
                ) {
                    Text("Close", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(4.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(materials) { index, material ->
                    MaterialCard(
                        index = index,
                        material = material,
                        isSelected = index == selectedIndex,
                        onClick = { onSelect(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MaterialCard(
    index: Int,
    material: GlbMaterial,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Color swatch
                ColorSwatch(material.baseColor)

                Spacer(Modifier.width(8.dp))

                Column {
                    Text(
                        text = material.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    Text(
                        text = "#$index",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Metallic
            PropertyRow("Metallic", material.metallic)

            Spacer(Modifier.height(2.dp))

            // Roughness
            PropertyRow("Roughness", material.roughness)

            // Emissive (only show if non-zero)
            if (material.emissive.any { it > 0.001f }) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Emissive",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.width(60.dp)
                    )
                    ColorSwatch(
                        floatArrayOf(material.emissive[0], material.emissive[1], material.emissive[2], 1f),
                        size = 14
                    )
                }
            }
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(60.dp)
        )
        LinearProgressIndicator(
            progress = { value },
            modifier = Modifier.weight(1f).height(4.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "%.0f%%".format(value * 100),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ColorSwatch(rgba: FloatArray, size: Int = 24) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Color(
                    red = rgba[0].coerceIn(0f, 1f),
                    green = rgba[1].coerceIn(0f, 1f),
                    blue = rgba[2].coerceIn(0f, 1f),
                    alpha = rgba.getOrElse(3) { 1f }.coerceIn(0f, 1f)
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
    )
}
