package com.hermes.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StatusChip(status: String) {
    val (bg, fg) = when (status.uppercase()) {
        "PENDING" -> Color(0xFFD29922) to Color.White
        "QUEUED" -> Color(0xFF58A6FF) to Color.White
        "RUNNING" -> Color(0xFF7C5CFC) to Color.White
        "COMPLETED" -> Color(0xFF39D353) to Color.White
        "FAILED" -> Color(0xFFF85149) to Color.White
        "CANCELLED" -> Color(0xFF6E7681) to Color.White
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.height(28.dp)
    ) {
        Text(
            status.replaceFirstChar { it.uppercase() },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium
        )
    }
}
