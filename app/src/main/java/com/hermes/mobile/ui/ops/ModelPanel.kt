package com.hermes.mobile.ui.ops

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.domain.model.ProviderModels
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.components.SkeletonList
import com.hermes.mobile.ui.theme.HermesMono

/**
 * The model picker, grouped by provider the way the server returns it.
 *
 * The expensive-model gate is a real dialog rather than a silent retry: the
 * server answers `confirm_required` with its own wording, and repeating that
 * wording is the honest way to ask.
 */
@Composable
fun ModelPanel(vm: OpsViewModel) {
    val catalog by vm.models.collectAsState()
    val pending by vm.pendingModel.collectAsState()
    val busy by vm.busy.collectAsState()

    LaunchedEffect(Unit) { vm.loadModels() }

    Column(Modifier.fillMaxSize()) {
        PanelProgress(vm, "models")
        when {
            catalog == null && busy == "models" ->
                SkeletonList(rows = 5, rowHeight = 56, modifier = Modifier.padding(16.dp))

            catalog == null || catalog!!.isEmpty -> EmptyState(
                icon = Icons.Outlined.Memory,
                title = "No models listed",
                hint = "Authenticate a provider on the desktop and this fills in. " +
                    "The picker only shows providers your PC can already reach.",
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                catalog!!.providers.filter { it.models.isNotEmpty() }.forEach { provider ->
                    item(key = "hdr-${provider.slug}") {
                        ProviderHeader(provider)
                    }
                    items(provider.models, key = { "${provider.slug}/$it" }) { model ->
                        ModelRow(
                            model = model,
                            selected = model == catalog!!.currentModel &&
                                provider.slug == catalog!!.currentProvider,
                            onClick = { vm.switchModel(provider.slug, model) },
                        )
                    }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = vm::dismissPendingModel,
            title = { Text("Use ${p.model}?") },
            text = { Text(p.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.switchModel(p.provider, p.model, confirmExpensive = true)
                        vm.dismissPendingModel()
                    },
                ) { Text("Use it") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissPendingModel) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProviderHeader(provider: ProviderModels) {
    SectionLabel(
        provider.label.ifBlank { provider.slug },
        trailing = {
            if (!provider.authenticated) MetaChip("not signed in")
        },
    )
}

@Composable
private fun ModelRow(model: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (selected) "$model, in use" else "Switch to $model"
            },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                model,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = HermesMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(18.dp))
            }
        }
    }
}
