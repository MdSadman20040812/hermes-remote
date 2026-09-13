package com.hermes.mobile.ui.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.components.Meter
import com.hermes.mobile.ui.components.NavRow
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * Everything you can do *to* the open session, in one sheet.
 *
 * A bottom sheet rather than an overflow menu because these are consequential
 * actions that deserve their labels and their token accounting on screen —
 * "compress" is not something anyone should trigger from a 40dp menu row
 * without knowing how full the context already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(vm: CockpitViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title by vm.activeTitle.collectAsState()
    val usage by vm.usage.collectAsState()
    val contextPercent by vm.contextPercent.collectAsState()

    val approvalMode by vm.approvalMode.collectAsState()
    val sessionAutonomous by vm.sessionAutonomous.collectAsState()

    var renaming by remember { mutableStateOf(false) }
    var draft by remember(title) { mutableStateOf(title) }

    LaunchedEffect(Unit) { vm.refreshAutonomy() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            if (renaming) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Session name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { renaming = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            if (draft.isNotBlank()) vm.rename(draft.trim())
                            renaming = false
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Save") }
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }

            // ---- context budget ----
            contextPercent?.let { percent ->
                SectionLabel("Context")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Meter(
                        fraction = percent / 100f,
                        modifier = Modifier.weight(1f),
                        tone = when {
                            percent >= 85 -> MaterialTheme.colorScheme.error
                            percent >= 65 -> MaterialTheme.hermes.warning
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("$percent%", style = MaterialTheme.typography.labelMedium)
                }
                usage?.let { u ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append("${u.calls} calls · ")
                            append("${u.input.thousands()} in · ")
                            append("${u.output.thousands()} out")
                            u.contextMax?.takeIf { it > 0 }?.let {
                                append(" · window ${it.thousands()}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = HermesMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                usage?.creditLines?.takeIf { it.isNotEmpty() }?.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- autonomy ----
            // This is the control that decides how much the PC does without
            // you. It lives in the session sheet, next to the context meter,
            // because both answer the same question: how far can I let this
            // run before I need to look at it again?
            SectionLabel("How much it decides alone")
            Text(
                "Applies to your whole PC, exactly like the desktop's approval " +
                    "setting — changing it here changes it there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AUTONOMY_MODES.forEach { (mode, label, blurb) ->
                    AutonomyRow(
                        label = label,
                        blurb = blurb,
                        selected = approvalMode == mode,
                        // Unknown/unloaded mode selects nothing rather than
                        // guessing: showing "Manual" as selected when the PC is
                        // actually on "off" is worse than showing no selection.
                        onClick = { vm.setApprovalMode(mode) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { vm.setSessionAutonomous(!sessionAutonomous) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Run this chat unattended", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Just this session — your PC's default stays as it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = sessionAutonomous,
                    onCheckedChange = { vm.setSessionAutonomous(it) },
                )
            }

            SectionLabel("Actions")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavRow(
                    title = "Rename",
                    subtitle = "Also renames it on the desktop",
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = { renaming = true },
                )
                NavRow(
                    title = "Branch",
                    subtitle = "Fork the history and continue on the copy",
                    icon = Icons.Outlined.CallSplit,
                    onClick = {
                        vm.branch()
                        onDismiss()
                    },
                )
                NavRow(
                    title = "Compress history",
                    subtitle = "Summarise older turns to free the context window",
                    icon = Icons.Outlined.Compress,
                    onClick = {
                        vm.compress()
                        onDismiss()
                    },
                )
                NavRow(
                    title = "Undo last exchange",
                    subtitle = "Interrupt the turn first if one is running",
                    icon = Icons.Outlined.Undo,
                    onClick = {
                        vm.undo()
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 1_234_567 → "1.2M". Raw token counts are unreadable at a glance. */
internal fun Long.thousands(): String = when {
    this >= 1_000_000 -> String.format("%.1fM", this / 1_000_000.0)
    this >= 1_000 -> String.format("%.1fk", this / 1_000.0)
    else -> toString()
}

/**
 * The three modes the gateway actually supports (`approvals.mode`), in
 * increasing order of trust. The copy is deliberately about consequences, not
 * about the mechanism: "every risky action asks first" is a promise the user
 * can check; "manual approval mode" is a label they have to decode.
 */
private val AUTONOMY_MODES = listOf(
    Triple("manual", "Ask me first", "Every risky command waits for your tap."),
    Triple("smart", "Ask only when it matters", "Your PC judges the risk and only interrupts for the real ones."),
    Triple("off", "Full autonomy", "Nothing waits for you. Best for long jobs you've already decided on."),
)

@Composable
private fun AutonomyRow(
    label: String,
    blurb: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$label. $blurb" },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
