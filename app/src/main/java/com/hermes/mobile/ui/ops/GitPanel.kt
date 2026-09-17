package com.hermes.mobile.ui.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.hermes.mobile.domain.model.GitFile
import com.hermes.mobile.domain.model.GitStatus
import com.hermes.mobile.ui.components.EmptyState
import com.hermes.mobile.ui.components.MetaChip
import com.hermes.mobile.ui.components.SkeletonList
import com.hermes.mobile.ui.theme.HermesMono
import com.hermes.mobile.ui.theme.hermes

/**
 * What the agent did to the working tree.
 *
 * Read-only on purpose. The question this answers from a phone is "is it safe
 * to let this keep going" — branch, drift from the remote, and which files
 * moved. Committing and pushing from a bus, without a diff you can actually
 * read, is how a bad turn becomes a bad commit.
 */
@Composable
fun GitPanel(vm: OpsViewModel) {
    val status by vm.git.collectAsState()
    val path by vm.gitPath.collectAsState()
    val busy by vm.busy.collectAsState()

    LaunchedEffect(Unit) { vm.loadGit() }

    Column(Modifier.fillMaxSize()) {
        PanelProgress(vm, "git")
        when {
            status == null && busy == "git" ->
                SkeletonList(rows = 5, rowHeight = 48, modifier = Modifier.padding(16.dp))

            status == null -> EmptyState(
                icon = Icons.Outlined.Difference,
                title = "No repository",
                hint = path?.let { "$it isn't a git working tree." }
                    ?: "The server didn't report a working directory to inspect.",
            )

            else -> {
                val s = status!!
                Column(Modifier.fillMaxSize()) {
                    GitHeader(s, path) { vm.loadGit(force = true) }
                    if (s.clean) {
                        EmptyState(
                            icon = Icons.Outlined.Difference,
                            title = "Working tree clean",
                            hint = "Nothing changed since the last commit.",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, bottom = 32.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(s.files, key = { it.path }) { FileRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHeader(status: GitStatus, path: String?, onRefresh: () -> Unit) {
    val semantics = MaterialTheme.hermes
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CallSplit,
                    contentDescription = null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    status.branch ?: "detached HEAD",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = HermesMono,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(36.dp).semantics {
                        contentDescription = "Refresh git status"
                    },
                ) { Icon(Icons.Outlined.Refresh, contentDescription = null, Modifier.size(18.dp)) }
            }

            path?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = HermesMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.ahead > 0) {
                    MetaChip("${status.ahead}", icon = Icons.Outlined.ArrowUpward)
                }
                if (status.behind > 0) {
                    MetaChip("${status.behind}", icon = Icons.Outlined.ArrowDownward)
                }
                if (status.added > 0) {
                    MetaChip("+${status.added}", tone = semantics.diffAdded)
                }
                if (status.removed > 0) {
                    MetaChip("−${status.removed}", tone = semantics.diffRemoved)
                }
                if (status.conflicted > 0) {
                    MetaChip(
                        "${status.conflicted} conflicted",
                        tone = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(
                "${status.changed} changed · ${status.staged} staged · " +
                    "${status.unstaged} unstaged · ${status.untracked} untracked",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileRow(file: GitFile) {
    val semantics = MaterialTheme.hermes
    val (mark, tone) = when {
        file.conflicted -> "!" to MaterialTheme.colorScheme.error
        file.untracked -> "?" to semantics.warning
        file.staged -> "+" to semantics.diffAdded
        else -> "~" to MaterialTheme.colorScheme.secondary
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mark,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = HermesMono,
            color = tone,
            modifier = Modifier.width(18.dp),
        )
        Text(
            file.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = HermesMono,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
