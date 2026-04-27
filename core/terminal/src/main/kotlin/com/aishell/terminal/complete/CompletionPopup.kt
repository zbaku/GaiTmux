package com.aishell.terminal.complete

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun CompletionPopup(
    items: List<CompletionItem>,
    selectedIndex: Int,
    onSelect: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(IntrinsicSize.Max),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        LazyColumn(
            modifier = Modifier.sizeIn(maxHeight = 200.dp)
        ) {
            itemsIndexed(items) { index, item ->
                CompletionItemRow(
                    item = item,
                    isSelected = index == selectedIndex,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
}

@Composable
fun CompletionItemRow(
    item: CompletionItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (item.type) {
                    CompletionType.COMMAND -> "$"
                    CompletionType.FILE -> "#"
                    CompletionType.DIRECTORY -> "/"
                    CompletionType.OPTION -> "-"
                    CompletionType.HISTORY -> "^"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = item.display,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
