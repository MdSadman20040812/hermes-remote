package com.hermes.mobile.ui.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.domain.model.ModelUsage
import com.hermes.mobile.domain.model.UsageReport
import com.hermes.mobile.ui.cockpit.thousands
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.Meter
import com.hermes.mobile.ui.components.SectionLabel
import com.hermes.mobile.ui.components.SkeletonList
import com.hermes.mobile.ui.theme.HermesMono

/**
 * What the agent has been costing.
 *
 * Per-model bars rather than a time-series chart: on a phone the question is
 * "which model is eating the budget", and a proportional bar answers that in
 * one glance where thirty daily points would not.
 */
@Composable
fun UsagePanel(vm: OpsViewModel) {
    val report by vm.usage.collectAsState()
    val busy by vm.busy.collectAsState()
    var days by remember { mutableIntStateOf(30) }

    LaunchedEffect(days) { vm.loadUsage(days) }

    Column(Modifier.fillMaxSize()) {
        PanelProgress(vm, "usage")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(7, 30, 90).forEach { window ->
                FilterChip(
                    selected = days == window,
                    onClick = { days = window },
                    label = { Text("${window}d") },
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .semantics { contentDescription = "Last $window days" },
                )
            }
        }

        when {
            report == null && busy == "usage" ->
                SkeletonList(rows = 5, rowHeight = 56, modifier = Modifier.padding(16.dp))

            report == null || report!!.totalTokens == 0L -> EmptyState(
                icon = Icons.Outlined.Insights,
                title = "No usage recorded",
                hint = "Nothing ran in the last $days days, or the session database " +
                    "hasn't recorded token counts yet.",
            )

            else -> UsageBody(report!!)
        }
    }
}

@Composable
private fun UsageBody(report: UsageReport) {
    val peak = report.byModel.maxOfOrNull { it.total }?.coerceAtLeast(1) ?: 1

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row {
                        Figure("Tokens", report.totalTokens.thousands(), Modifier.weight(1f))
                        Figure(
                            "Cost",
                            "$" + String.format("%.2f", report.estimatedCostUsd),
                            Modifier.weight(1f),
                        )
                    }
                    Row {
                        Figure("Sessions", report.totalSessions.toString(), Modifier.weight(1f))
                        Figure("API calls", report.totalApiCalls.thousands(), Modifier.weight(1f))
                    }
                    Text(
                        "${report.totalInput.thousands()} in · " +
                            "${report.totalOutput.thousands()} out · " +
                            "${report.totalCacheRead.thousands()} cached",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = HermesMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (report.byModel.isNotEmpty()) {
            item { SectionLabel("By model") }
            items(report.byModel, key = { it.model }) { ModelUsageRow(it, peak) }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.headlineSmall, fontFamily = HermesMono)
    }
}

@Composable
private fun ModelUsageRow(usage: ModelUsage, peak: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                usage.model,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = HermesMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                usage.total.thousands(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(5.dp))
        Meter(
            fraction = usage.total.toFloat() / peak.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (usage.estimatedCostUsd > 0) {
            Spacer(Modifier.height(3.dp))
            Text(
                "$" + String.format("%.2f", usage.estimatedCostUsd) +
                    " · ${usage.sessions} sessions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
