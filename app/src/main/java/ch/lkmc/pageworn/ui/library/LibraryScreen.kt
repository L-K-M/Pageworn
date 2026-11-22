package ch.lkmc.pageworn.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.EmptyStateView
import ch.lkmc.pageworn.ui.components.PencilIcon
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.SectionHeader
import ch.lkmc.pageworn.ui.components.StatusLineView
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.TransferIndicator
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CatalogRow
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(state: LibraryUiState, listState: LazyListState, onQueryChange: (String) -> Unit, onSelectLibrary: (String) -> Unit, onSelectSort: (CatalogSort) -> Unit, onOpenBook: (BookKey) -> Unit, onDownload: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit, onAction: (UiAction) -> Unit, onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    // A new order starts at its top, where the books it was chosen for are.
    var shownSort by remember { mutableStateOf(state.sort) }
    LaunchedEffect(state.sort) {
        if (shownSort == state.sort) return@LaunchedEffect
        shownSort = state.sort
        listState.scrollToItem(0)
    }
    LazyColumn(state = listState, contentPadding = contentPadding) {
        item(key = "heading") { SectionHeader(state.heading, onOpenSettings) }
        if (state.showSelectorAndSearch) {
            if (state.libraries.size > 1) item(key = "selector") {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    OutlinedTextField(value = state.libraries.firstOrNull { it.id == state.selectedLibraryId }?.name ?: "Choose library", onValueChange = {}, readOnly = true, label = { Text("Library") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        state.libraries.forEach { choice -> DropdownMenuItem(text = { Text(choice.name) }, onClick = { expanded = false; onSelectLibrary(choice.id) }) }
                    }
                }
            }
            item(key = "search") {
                OutlinedTextField(value = state.query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), placeholder = { Text("Search title or author") }, leadingIcon = { PencilIcon(R.drawable.pw_pencil_search, null, 24.dp) }, trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) { Text("×", modifier = Modifier.semantics { contentDescription = "Clear search" }) }
                }, singleLine = false)
            }
            item(key = "sort") { LibrarySortControl(state.sort, onSelectSort) }
        }
        state.status?.let { line -> item(key = "status") { StatusLineView(line, onAction) } }
        if (state.empty != null) item(key = "empty") { EmptyStateView(state.empty, onAction) }
        else items(state.rows.size, key = { state.rows[it].face.key.mediaId }) { index ->
            CatalogBookRow(state.rows[index], onOpenBook, onDownload, onPlayPause, onPauseTransfer, onResumeTransfer, onCancelTransfer)
            if (index != state.rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
        }
    }
}

@Composable
private fun CatalogBookRow(row: CatalogRow, onOpenBook: (BookKey) -> Unit, onDownload: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit) {
    val face = row.face
    val compact = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().semantics { if (row.action is CatalogAction.Ready) stateDescription = "Downloaded" }.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(76.dp).clickable(role = Role.Button) { onOpenBook(face.key) }.semantics { contentDescription = "Open ${face.title}" }) { BookCover(face, 76.dp, showRibbon = (row.action as? CatalogAction.Ready)?.start == StartAction.RESUME) }
            Column(Modifier.weight(1f)) {
                Text(face.title, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                face.author?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
                val message = when (row.action) {
                    CatalogAction.DownloadDisabled -> row.message ?: Copy.DOWNLOAD_DISABLED
                    CatalogAction.Unsupported -> row.message ?: Copy.UNSUPPORTED_FORMAT
                    CatalogAction.RepairNeeded -> row.message ?: "Repair needed"
                    else -> row.message
                }
                message?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                if (row.action is CatalogAction.Ready) Text("Downloaded", Modifier.semantics { stateDescription = "Downloaded" }, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                if (row.action is CatalogAction.Transferring && compact) Text(row.action.transfer.stateText, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
            }
            if (!compact) CatalogActionView(row, onDownload, onPlayPause, onPauseTransfer, onResumeTransfer)
        }
        if (compact) CatalogActionView(row, onDownload, onPlayPause, onPauseTransfer, onResumeTransfer)
        val transfer = (row.action as? CatalogAction.Transferring)?.transfer
        if (transfer?.canCancel == true) TextAction(Copy.CANCEL, spokenLabel = "Cancel download of ${face.title}") { onCancelTransfer(transfer.jobId) }
    }
}

@Composable
private fun CatalogActionView(row: CatalogRow, onDownload: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit) {
    when (val action = row.action) {
        CatalogAction.Download -> IconButton(onClick = { onDownload(row.face.key) }, modifier = Modifier.size(48.dp)) { PencilIcon(R.drawable.pw_pencil_download, "Download ${row.face.title}") }
        is CatalogAction.Ready -> PlayPauseKey(action.showsPause, row.face.title, 36.dp, starting = action.starting, start = action.start) { onPlayPause(row.face.key, it) }
        is CatalogAction.Transferring -> {
            val t = action.transfer
            val callback = when { t.canPause -> ({ onPauseTransfer(t.jobId) }); t.canResume -> ({ onResumeTransfer(t.jobId) }); else -> null }
            Box((if (callback != null) Modifier.clickable(role = Role.Button, onClick = callback) else Modifier).semantics { if (callback != null) contentDescription = "${if (t.canPause) "Pause" else "Resume"} download of ${row.face.title}" }) { TransferIndicator(t, compact = true) }
        }
        else -> Unit
    }
}
