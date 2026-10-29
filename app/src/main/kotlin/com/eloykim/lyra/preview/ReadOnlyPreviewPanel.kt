package com.eloykim.lyra.preview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Read-only document surface shared by all file previews. It deliberately has
 * no edit actions, script host, file picker, or network loading.
 */
@Composable
fun ReadOnlyPreviewPanel(document: PreviewDocument, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        SelectionContainerCompat {
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                item {
                    Text(document.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                }
                items(document.blocks) { block ->
                    when (block) {
                        is PreviewBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 6.dp))
                        is PreviewBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 5.dp))
                        is PreviewBlock.Code -> Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 7.dp))
                        is PreviewBlock.Slide -> Column(Modifier.padding(vertical = 7.dp)) { Text("Slide ${block.number}", style = MaterialTheme.typography.titleSmall); Text(block.text) }
                        is PreviewBlock.Table -> PreviewTable(block.rows)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewTable(rows: List<List<String>>) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 7.dp)) {
        Column {
            rows.forEach { row ->
                Row {
                    row.forEach { cell -> Text(cell, modifier = Modifier.padding(end = 18.dp, bottom = 5.dp), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun SelectionContainerCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}
