package ch.lkmc.pageworn.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
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
import ch.lkmc.pageworn.ui.components.ROW_COVER
import ch.lkmc.pageworn.ui.components.ROW_COVER_LARGE_TYPE
import ch.lkmc.pageworn.ui.components.ROW_KEY
import ch.lkmc.pageworn.ui.components.SectionHeader
import ch.lkmc.pageworn.ui.components.StatusLineView
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.TransferIndicator
import ch.lkmc.pageworn.ui.components.paperFieldColors
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CatalogRow
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.theme.LocalHighContrast
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
                    OutlinedTextField(value = state.libraries.firstOrNull { it.id == state.selectedLibraryId }?.name ?: "Choose library", onValueChange = {}, readOnly = true, label = { Text("Library") }, trailingIcon = { PencilIcon(R.drawable.pw_pencil_collapse, null, 20.dp) }, shape = RoundedCornerShape(12.dp), colors = paperFieldColors(), modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }, shape = RoundedCornerShape(12.dp), containerColor = PagewornTheme.colors.raisedSheet) {
                        state.libraries.forEach { choice -> DropdownMenuItem(text = { Text(choice.name, style = PagewornTheme.type.author) }, onClick = { expanded = false; onSelectLibrary(choice.id) }) }
                    }
                }
            }
            item(key = "search") { SearchField(state.query, onQueryChange) }
            item(key = "sort") { LibrarySortControl(state.sort, onSelectSort) }
        }
        state.status?.let { line -> item(key = "status") { StatusLineView(line, onAction) } }
        if (state.empty != null) item(key = "empty") { EmptyStateView(state.empty, onAction) }
        else items(state.rows.size, key = { state.rows[it].face.key.mediaId }) { index ->
            // A book that appears or moves settles into place instead of jumping.
            Column(Modifier.animateItem()) {
                CatalogBookRow(state.rows[index], onOpenBook, onDownload, onPlayPause, onPauseTransfer, onResumeTransfer, onCancelTransfer)
                if (index != state.rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
            }
        }
    }
}

@Composable
private fun CatalogBookRow(row: CatalogRow, onOpenBook: (BookKey) -> Unit, onDownload: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit) {
    val face = row.face
    val compact = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp)) {
            val coverSize = if (compact) ROW_COVER_LARGE_TYPE else ROW_COVER
            Box(Modifier.width(coverSize).clickable(role = Role.Button) { onOpenBook(face.key) }.semantics { contentDescription = "Open ${face.title}" }) { BookCover(face, coverSize, showRibbon = (row.action as? CatalogAction.Ready)?.start == StartAction.RESUME) }
            // The Play key says it is downloaded to the eye, and the title's state to TalkBack.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { if (row.action is CatalogAction.Ready) stateDescription = "Downloaded" }) {
                Text(face.title, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                face.author?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author) }
                val message = when (row.action) {
                    CatalogAction.DownloadDisabled -> row.message ?: Copy.DOWNLOAD_DISABLED
                    CatalogAction.Unsupported -> row.message ?: Copy.UNSUPPORTED_FORMAT
                    CatalogAction.RepairNeeded -> row.message ?: "Repair needed"
                    else -> row.message
                }
                message?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                // The phase belongs beside the ring at every text size, so a paused or waiting
                // transfer never reads as active.
                if (row.action is CatalogAction.Transferring) Text(row.action.transfer.stateText, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
            }
            if (!compact) CatalogActionView(row, onDownload, onPlayPause, onPauseTransfer, onResumeTransfer)
        }
        if (compact) CatalogActionView(row, onDownload, onPlayPause, onPauseTransfer, onResumeTransfer)
        val transfer = (row.action as? CatalogAction.Transferring)?.transfer
        if (transfer?.canCancel == true) Box(Modifier.padding(start = if (compact) 0.dp else ACTIONS_INDENT)) {
            TextAction(Copy.CANCEL, spokenLabel = "Cancel download of ${face.title}") { onCancelTransfer(transfer.jobId) }
        }
    }
}

@Composable
private fun CatalogActionView(row: CatalogRow, onDownload: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit) {
    when (val action = row.action) {
        CatalogAction.Download -> IconButton(onClick = { onDownload(row.face.key) }, modifier = Modifier.size(48.dp)) { PencilIcon(R.drawable.pw_pencil_download, "Download ${row.face.title}", 26.dp) }
        is CatalogAction.Ready -> PlayPauseKey(action.showsPause, row.face.title, ROW_KEY, starting = action.starting, start = action.start) { onPlayPause(row.face.key, it) }
        is CatalogAction.Transferring -> {
            val t = action.transfer
            val callback = when { t.canPause -> ({ onPauseTransfer(t.jobId) }); t.canResume -> ({ onResumeTransfer(t.jobId) }); else -> null }
            Box((if (callback != null) Modifier.clickable(role = Role.Button, onClick = callback) else Modifier).semantics { if (callback != null) contentDescription = "${if (t.canPause) "Pause" else "Resume"} download of ${row.face.title}" }) { TransferIndicator(t, compact = true) }
        }
        else -> Unit
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = PagewornTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val edge = if (focused || LocalHighContrast.current) colors.primaryInk else colors.controlEdge(highContrast = false)
    BasicTextField(
        query,
        // A hardware keyboard's Enter or a pasted multi-line text can still put newlines into a
        // multi-line field, so the query never carries them (CRLF collapses to one space).
        { onQueryChange(it.replace(Regex("\\r\\n|\\r|\\n"), " ")) },
        // The hint leaves once there is a query, so the field carries the name itself.
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).semantics { contentDescription = SEARCH_HINT },
        textStyle = PagewornTheme.type.empty.copy(color = colors.primaryInk),
        cursorBrush = SolidColor(colors.accent),
        interactionSource = interaction,
        // One line at normal font scales; from 150% text the query may wrap to two lines so it
        // stays readable, but the field never grows beyond that and pushes the list down.
        // The Search IME action dismisses the keyboard; the query itself drives the search.
        singleLine = LocalDensity.current.fontScale < 1.5f,
        maxLines = 2, // only applies when singleLine = false (fontScale >= 1.5)
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // The search is query-driven; the Search key just dismisses the keyboard.
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        decorationBox = { field ->
            Row(
                Modifier.heightIn(min = 48.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), shape).border(if (focused) 2.dp else 1.dp, edge, shape).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PencilIcon(R.drawable.pw_pencil_search, null, 20.dp)
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (query.isEmpty()) Text(SEARCH_HINT, color = colors.secondaryInk, style = PagewornTheme.type.empty)
                    field()
                }
                if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                    Text("×", Modifier.semantics { contentDescription = "Clear search" }, color = colors.primaryInk, style = PagewornTheme.type.sheetTitle)
                }
            }
        },
    )
}

/** Lines a transfer's action up under its title: the cover and gap less the action's own padding. */
private val ACTIONS_INDENT = ROW_COVER + 14.dp - 8.dp

private const val SEARCH_HINT = "Search title or author"
