package com.hermes.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One drawn icon per tool family.
 *
 * The transcript previously labelled every tool call with a gear emoji, which
 * made a file write and a web fetch look identical at a glance — exactly the
 * distinction you want while skim-reading what the agent did while you were
 * away. Matching is on the tool name because the server's tool set is open:
 * plugins and MCP servers add names this app has never heard of, so unknown
 * falls back to a neutral wrench rather than guessing.
 */
fun toolIconFor(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.contains("bash") || n.contains("shell") || n.contains("exec") ||
            n.contains("terminal") || n.contains("command") -> Icons.Outlined.Terminal

        n.contains("edit") || n.contains("write") || n.contains("patch") ||
            n.contains("replace") -> Icons.Outlined.Edit

        n.contains("diff") || n.contains("git") -> Icons.Outlined.Difference

        n.contains("read") || n.contains("cat") || n.contains("view") ||
            n.contains("file") -> Icons.Outlined.Description

        n.contains("glob") || n.contains("ls") || n.contains("dir") ||
            n.contains("tree") -> Icons.Outlined.FolderOpen

        n.contains("grep") || n.contains("search") || n.contains("find") -> Icons.Outlined.Search

        n.contains("web") || n.contains("fetch") || n.contains("http") ||
            n.contains("browser") || n.contains("url") -> Icons.Outlined.Language

        n.contains("mcp") || n.contains("api") -> Icons.Outlined.Api

        n.contains("image") || n.contains("screenshot") || n.contains("vision") ->
            Icons.Outlined.Image

        n.contains("todo") || n.contains("task") || n.contains("plan") ->
            Icons.AutoMirrored.Outlined.ListAlt

        n.contains("skill") || n.contains("agent") || n.contains("think") ->
            Icons.Outlined.AutoAwesome

        n.contains("memory") || n.contains("recall") -> Icons.Outlined.Memory

        else -> Icons.Outlined.Build
    }
}
