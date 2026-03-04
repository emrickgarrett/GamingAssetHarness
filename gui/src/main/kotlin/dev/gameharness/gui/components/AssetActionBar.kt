package dev.gameharness.gui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gameharness.core.model.AssetDecision
import dev.gameharness.core.model.AssetReviewDecision

/**
 * Approve/deny action bar shown when an asset is being previewed. Provides an optional
 * feedback text field for the user to explain why they are denying the asset.
 */
@Composable
fun AssetActionBar(
    isVisible: Boolean,
    onDecision: (AssetReviewDecision) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = isVisible, modifier = modifier) {
        var showFeedback by remember { mutableStateOf(false) }
        var feedbackText by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = {
                        onDecision(AssetReviewDecision(AssetDecision.APPROVED))
                        showFeedback = false
                        feedbackText = ""
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("Approve")
                }

                OutlinedButton(
                    onClick = {
                        if (showFeedback && feedbackText.isNotBlank()) {
                            onDecision(AssetReviewDecision(AssetDecision.DENIED, feedbackText.trim()))
                            showFeedback = false
                            feedbackText = ""
                        } else {
                            showFeedback = true
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (showFeedback) "Submit Feedback" else "Deny")
                }
            }

            AnimatedVisibility(visible = showFeedback) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("What would you like changed?") },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.error,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            onDecision(AssetReviewDecision(AssetDecision.DENIED))
                            showFeedback = false
                            feedbackText = ""
                        }) {
                            Text("Deny without feedback")
                        }
                    }
                }
            }
        }
    }
}
