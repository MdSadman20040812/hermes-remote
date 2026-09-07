package com.hermes.mobile.ui.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.SkeletonList

/**
 * Every skill the agent can reach, with a switch.
 *
 * This is the one panel that changes what the agent is *able to do*, so the
 * provenance of each skill — bundled, installed from the hub, or written
 * locally — stays visible rather than being flattened into a name.
 */
@Composable
fun SkillsPanel(vm: OpsViewModel) {
    val skills by vm.skills.collectAsState()
    val busy by vm.busy.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.loadSkills() }

    val visible = remember(skills, query) {
        if (query.isBlank()) skills
        else skills.filter {
            it.name.contains(query, true) || it.description.contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PanelProgress(vm, "skills")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search skills") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = {
                Icon(Icons.Outlined.Search, contentDescription = null, Modifier.size(18.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = "" },
                        modifier = Modifier.semantics { contentDescription = "Clear search" },
                    ) { Icon(Icons.Outlined.Close, contentDescription = null, Modifier.size(18.dp)) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            skills.isEmpty() && busy == "skills" ->
                SkeletonList(rows = 6, rowHeight = 64, modifier = Modifier.padding(16.dp))

            skills.isEmpty() -> EmptyState(
                icon = Icons.Outlined.AutoAwesome,
                title = "No skills installed",
                hint = "Skills come from the bundled set, the hub, or ones you write. " +
                    "Install them on the desktop and they appear here.",
            )

            visible.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Search,
                title = "No match",
                hint = "No skill name or description mentions \"$query\".",
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.name }) { skill ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                if (skill.description.isNotBlank()) {
                                    Text(
                                        skill.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.size(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (skill.provenance.isNotBlank()) MetaChip(skill.provenance)
                                    if (skill.usage > 0) MetaChip("used ${skill.usage}×")
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = { vm.toggleSkill(skill) },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        if (skill.enabled) "Disable ${skill.name}"
                                        else "Enable ${skill.name}"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
