package ch.lkmc.pageworn.ui.downloaded

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.EmptyStateView
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.ROW_COVER
import ch.lkmc.pageworn.ui.components.ROW_COVER_LARGE_TYPE
import ch.lkmc.pageworn.ui.components.ribbonSpace
import ch.lkmc.pageworn.ui.components.SectionHeader
import ch.lkmc.pageworn.ui.components.StatusLineView
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.TransferIndicator
import ch.lkmc.pageworn.ui.components.TransferPauseKey
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.DownloadedUiState
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.ReadyBadge
import ch.lkmc.pageworn.ui.model.ReadyBookRow
import ch.lkmc.pageworn.ui.model.TransferRow
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/** The play key of a cover: a smaller face than a row's, inside the same 48 dp target. */
private val GRID_KEY = 36.dp
private val GRID_KEY_TARGET = 48.dp

/** Space between covers, and the inset that keeps the outer ones off the screen's edges. */
private val GRID_GUTTER = 8.dp
private val GRID_CELL_INSET = 6.dp

/**
 * The narrowest a cover cell may be before the grid gives the shelf another column. A cover must
 * stay wider than the play key's target, which sits over its lower corner: a narrower cover would
 * let the target reach the cover's centre, and a tap on the cover would start the book.
 */
private val GRID_CELL_MIN = GRID_KEY_TARGET * 2 + GRID_CELL_INSET * 2 + 4.dp

/** A cover no wider than this, so a wide screen keeps shelf-sized books instead of posters. */
private val MAX_GRID_COVER = 120.dp

/** A heading, a status line and a transfer take the grid's whole width; only the books share rows. */
private fun LazyGridItemSpanScope.fullWidth() = GridItemSpan(maxLineSpan)

@Composable
fun DownloadedScreen(state: DownloadedUiState, listState: LazyGridState, onOpenBook: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onRetryTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit, onAction: (UiAction) -> Unit, onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GRID_CELL_MIN),
        state = listState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
    ) {
        item(key = "heading", span = { fullWidth() }) {
            Column {
                SectionHeader(state.heading, onOpenSettings)
                PaperRule()
            }
        }
        state.status?.let { item(key = "status", span = { fullWidth() }) { StatusLineView(it, onAction) } }
        if (state.empty != null) item(key = "empty", span = { fullWidth() }) { EmptyStateView(state.empty, onAction) }
        else {
            if (state.transfers.isNotEmpty()) item(key = "downloading", span = { fullWidth() }) {
                // Fades with the last transfer row instead of popping out a frame early.
                Text("Downloading", Modifier.animateItem().padding(start = 20.dp, top = 12.dp, bottom = 8.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.sheetTitle.copy(fontSize = PagewornTheme.type.rowTitle.fontSize * 1.15f))
            }
            itemsIndexed(state.transfers, key = { _, row -> "transfer:${row.transfer.jobId}" }, span = { _, _ -> fullWidth() }) { index, row ->
                // A finished transfer fades out of the group above as its book fades in below.
                Column(Modifier.animateItem()) {
                    DownloadTransferRow(row, onOpenBook, onPauseTransfer, onResumeTransfer, onRetryTransfer, onCancelTransfer)
                    // The divider after the last transfer separates the sections while books
                    // follow, and stays away when it is the bottom of the list.
                    if (index != state.transfers.lastIndex || state.books.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
                }
            }
            // The listener knows these books: the covers alone tell them apart, and the ribbon
            // marks a saved place.
            items(state.books, key = { it.face.key.mediaId }) { row ->
                DownloadedBookCell(row, onOpenBook, onPlayPause)
            }
        }
    }
}

@Composable
private fun DownloadTransferRow(row: TransferRow, onOpenBook: (BookKey) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onRetryTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit) {
    val compact = LocalDensity.current.fontScale >= 1.5f
    val transfer = row.transfer
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp)) {
            val coverSize = if (compact) ROW_COVER_LARGE_TYPE else ROW_COVER
            Box(Modifier.width(coverSize).clickable(role = Role.Button) { onOpenBook(row.face.key) }.semantics { contentDescription = "Open ${row.face.title}" }) { BookCover(row.face, coverSize) }
            Column(Modifier.weight(1f)) {
                Text(row.face.title, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                row.face.author?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author) }
                if (!compact) TransferIndicator(transfer, compact = false)
            }
            if (transfer.canPause && !compact) TransferPauseKey(row.face.title) { onPauseTransfer(transfer.jobId) }
        }
        if (compact) TransferIndicator(transfer, compact = false)
        if (transfer.canPause && compact) TextAction(Copy.PAUSE, spokenLabel = "Pause download of ${row.face.title}") { onPauseTransfer(transfer.jobId) }
        FlowRow(Modifier.padding(start = if (compact) 0.dp else ACTIONS_INDENT)) {
            if (transfer.canResume) TextAction("Resume", spokenLabel = "Resume download of ${row.face.title}") { onResumeTransfer(transfer.jobId) }
            if (transfer.canRetry) TextAction(Copy.RETRY, spokenLabel = "Retry download of ${row.face.title}") { onRetryTransfer(transfer.jobId) }
            if (transfer.canCancel) TextAction(Copy.CANCEL, spokenLabel = "Cancel download of ${row.face.title}") { onCancelTransfer(transfer.jobId) }
        }
    }
}

@Composable
private fun LazyGridItemScope.DownloadedBookCell(row: ReadyBookRow, onOpenBook: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit) {
    BoxWithConstraints(Modifier.animateItem().padding(horizontal = GRID_CELL_INSET)) {
        val cover = minOf(maxWidth, MAX_GRID_COVER)
        // The ribbon hangs below the art. Holding its space whether or not the book has a saved
        // place keeps the cover, its key and the shelf around them still when one is saved.
        val ribbon = ribbonSpace(cover)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(cover, cover + ribbon), contentAlignment = Alignment.TopStart) {
                BookCover(
                    row.face, cover, showRibbon = row.hasPlace,
                    modifier = Modifier.clickable(role = Role.Button) { onOpenBook(row.face.key) }.semantics {
                        contentDescription = "Open ${row.face.title}"
                        row.spokenState()?.let { stateDescription = it }
                    },
                )
                // Over the cover's lower corner, flush with its edges: the key's 48 dp target must
                // stay clear of the cover's centre, or a tap on the cover would start the book.
                if (row.playable) Box(Modifier.align(Alignment.BottomEnd).padding(bottom = ribbon)) {
                    PlayPauseKey(row.showsPause, row.face.title, GRID_KEY, starting = row.starting, start = row.start) { onPlayPause(row.face.key, it) }
                }
            }
            // A damaged edition is the one state a cover cannot show, and the only one that stops
            // the book from playing, so it keeps its label and its reason under the art. The
            // reason is what details offers to repair from.
            (row.badge as? ReadyBadge.RepairNeeded)?.let { repair ->
                Text(Copy.REPAIR_NEEDED, Modifier.padding(top = 2.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (repair.message.isNotBlank()) Text(repair.message, Modifier.padding(top = 2.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * What a cover alone does not say, for TalkBack: the local state the shelf used to print under a
 * book's title. A repair-needed edition says so under its cover instead.
 */
private fun ReadyBookRow.spokenState(): String? = when (val badge = badge) {
    ReadyBadge.New -> "New"
    ReadyBadge.Finished -> "Finished"
    is ReadyBadge.InProgress -> badge.text
    is ReadyBadge.RepairNeeded -> null
}

/** Lines a transfer's actions up under its title: the cover and gap less the action's own padding. */
private val ACTIONS_INDENT = ROW_COVER + 14.dp - 8.dp
