package net.packset.app.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.packset.rig.RigProfile

/**
 * Radio-button card list for rig selection (spec
 * 2026-07-12-rig-card-list-design): a None card, one card per saved rig
 * with Edit/delete, and a dashed + Add Rig button. Replaces the dropdown.
 */
@Composable
fun RigCardList(
    profiles: List<RigProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
    onEdit: (RigProfile) -> Unit,
    onDelete: (RigProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RigCard(
            title = "None",
            subtitle = "No radio connected",
            selected = selectedId == null,
            enabled = enabled,
            onClick = { onSelect(null) },
        )
        profiles.forEach { profile ->
            RigCard(
                title = profile.name,
                subtitle = RigCardSummary.subtitle(profile),
                selected = profile.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(profile.id) },
                trailing = {
                    TextButton(onClick = { onEdit(profile) }, enabled = enabled) { Text("Edit") }
                    IconButton(onClick = { onDelete(profile) }, enabled = enabled) {
                        Icon(Icons.Filled.Close, contentDescription = "Delete ${profile.name}")
                    }
                },
            )
        }
        val atCap = profiles.size >= RigProfileList.MAX
        AddRigButton(onClick = onAdd, enabled = enabled && !atCap)
        if (atCap) {
            Text(
                "Maximum of ${RigProfileList.MAX} rigs.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RigCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder(enabled)
        },
        colors = if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.outlinedCardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke(this)
        }
    }
}

@Composable
private fun AddRigButton(onClick: () -> Unit, enabled: Boolean) {
    val outline = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                    ),
                )
            },
    ) {
        Text("+ Add Rig")
    }
}
