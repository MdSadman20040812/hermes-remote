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
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
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
import com.hermes.mobile.domain.model.CronJob
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.PulseDot
import com.hermes.mobile.ui.components.SkeletonList
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * Scheduled jobs, and the ability to run one now.
 *
 * "Run now" is the reason this panel is worth having on a phone: noticing that
 * the nightly job should go early, and triggering it from the bus, is the
 * whole use case. Creating jobs stays on the desktop, where the full form
 * (delivery target, skills, working directory) has room to be filled in
 * honestly.
 */
@Composable
fun CronPanel(vm: OpsViewModel) {
    val jobs by vm.cron.collectAsState()
    val busy by vm.busy.collectAsState()

    LaunchedEffect(Unit) { vm.loadCron() }

    Column(Modifier.fillMaxSize()) {
        PanelProgress(vm, "cron")
        when {
            jobs.isEmpty() && busy == "cron" ->
                SkeletonList(rows = 4, rowHeight = 80, modifier = Modifier.padding(16.dp))

            jobs.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Schedule,
                title = "Nothing scheduled",
                hint = "Cron jobs created on the desktop show up here, where you can " +
                    "pause them or run one early.",
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job -> CronRow(job, vm) }
            }
        }
    }
}

@Composable
private fun CronRow(job: CronJob, vm: OpsViewModel) {
    val semantics = MaterialTheme.hermes
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(
                    color = if (job.enabled) semantics.online else semantics.offline,
                    animating = false,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    job.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MetaChip(job.schedule.ifBlank { "manual" })
            }

            if (job.prompt.isNotBlank()) {
                Text(
                    job.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val timing = listOfNotNull(
                job.lastRun?.let { "last $it" },
                job.nextRun?.let { "next $it" },
                job.deliver.takeIf { it.isNotBlank() }?.let { "to $it" },
            )
            if (timing.isNotEmpty()) {
                Text(
                    timing.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = HermesMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { vm.cronAction(job, CronAction.RUN) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Run ${job.name} now"
                    },
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run now")
                }
                TextButton(
                    onClick = {
                        vm.cronAction(
                            job,
                            if (job.enabled) CronAction.PAUSE else CronAction.RESUME,
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription =
                            if (job.enabled) "Pause ${job.name}" else "Resume ${job.name}"
                    },
                ) {
                    Icon(
                        if (job.enabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (job.enabled) "Pause" else "Resume")
                }
            }
        }
    }
}
