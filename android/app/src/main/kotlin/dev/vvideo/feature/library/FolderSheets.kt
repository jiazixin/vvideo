package dev.vvideo.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.vvideo.R
import dev.vvideo.model.Sort
import dev.vvideo.model.SortDirection
import dev.vvideo.model.SortField
import dev.vvideo.ui.component.ButtonKind
import dev.vvideo.ui.component.VVBottomSheet
import dev.vvideo.ui.component.VVButton
import dev.vvideo.ui.component.pathText
import dev.vvideo.ui.theme.LocalVVColors

@Composable
fun sortLabel(sort: Sort): String = stringResource(
    R.string.sort_label,
    stringResource(sortFieldLabel(sort.field)),
    stringResource(sortDirectionLabel(sort.field, sort.direction))
)

private fun sortFieldLabel(field: SortField) = when (field) {
    SortField.Name -> R.string.file_name
    SortField.Modified -> R.string.modified_time
    SortField.Capture -> R.string.capture_time
}

// Names read A to Z rather than oldest to newest.
private fun sortDirectionLabel(field: SortField, direction: SortDirection) = when {
    field == SortField.Name && direction == SortDirection.Ascending -> R.string.name_asc
    field == SortField.Name -> R.string.name_desc
    direction == SortDirection.Ascending -> R.string.oldest_first
    else -> R.string.newest_first
}

@Composable
fun SortSheet(state: LibraryUiState, onDismiss: () -> Unit, onSort: (Sort) -> Unit) {
    VVBottomSheet(stringResource(R.string.sort_title), onDismiss) {
        Text(
            stringResource(R.string.sort_scope, state.directory?.name.orEmpty()),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = LocalVVColors.current.textSecondary
        )
        SortField.entries.forEach { field ->
            Row(
                Modifier.fillMaxWidth().clickable { onSort(state.sort.copy(field = field)) }
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(state.sort.field == field, { onSort(state.sort.copy(field = field)) })
                Column {
                    Text(stringResource(sortFieldLabel(field)))
                    if (field == SortField.Capture) Text(
                        stringResource(R.string.sort_capture_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalVVColors.current.textSecondary
                    )
                }
            }
        }
        Text(
            stringResource(R.string.order),
            Modifier.padding(20.dp),
            color = LocalVVColors.current.accentText,
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SortDirection.entries.forEach { direction ->
                val label = stringResource(sortDirectionLabel(state.sort.field, direction))
                VVButton(
                    if (state.sort.direction == direction) stringResource(
                        R.string.selected_option,
                        label
                    ) else label,
                    { onSort(state.sort.copy(direction = direction)) },
                    Modifier.weight(1f),
                    kind = ButtonKind.Secondary
                )
            }
        }
    }
}

@Composable
fun FolderInfoSheet(state: LibraryUiState, onDismiss: () -> Unit) {
    VVBottomSheet(stringResource(R.string.folder_info), onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(pathText(state.path))
            Text(sortLabel(state.sort), color = LocalVVColors.current.textSecondary)
        }
    }
}
