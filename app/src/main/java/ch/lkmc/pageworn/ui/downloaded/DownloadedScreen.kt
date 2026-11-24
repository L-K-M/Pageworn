package ch.lkmc.pageworn.ui.downloaded

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.EmptyStateView
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.ROW_COVER
import ch.lkmc.pageworn.ui.components.ROW_COVER_LARGE_TYPE
import ch.lkmc.pageworn.ui.components.ROW_KEY
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

@Composable
fun DownloadedScreen(state: DownloadedUiState, listState: LazyListState, onOpenBook: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onRetryTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit, onAction: (UiAction) -> Unit, onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    LazyColumn(state = listState, contentPadding = contentPadding) {
        item(key = "heading") {
            Column {
                SectionHeader(state.heading, onOpenSettings)
                PaperRule()
            }
        }
        state.status?.let { item(key = "status") { StatusLineView(it, onAction) } }
        if (state.empty != null) item(key = "empty") { EmptyStateView(state.empty, onAction) }
        else {
            if (state.transfers.isNotEmpty()) item(key = "downloading") {
                // Fades with the last transfer row instead of popping out a frame early.
                Text("Downloading", Modifier.animateItem().padding(start = 20.dp, top = 12.dp, bottom = 8.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.sheetTitle.copy(fontSize = PagewornTheme.type.rowTitle.fontSize * 1.15f))
            }
            items(state.transfers.size, key = { "transfer:${state.transfers[it].transfer.jobId}" }) { index ->
                // A finished transfer fades out of the group above as its book fades in below.
                Column(Modifier.animateItem()) {
                    DownloadTransferRow(state.transfers[index], onOpenBook, onPauseTransfer, onResumeTransfer, onRetryTransfer, onCancelTransfer)
                    // The divider after the last transfer separates the sections while books
                    // follow, and stays away when it is the bottom of the list.
                    if (index != state.transfers.lastIndex || state.books.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
                }
            }
            items(state.books.size, key = { state.books[it].face.key.mediaId }) { index ->
                Column(Modifier.animateItem()) {
                    DownloadedBookRow(state.books[index], onOpenBook, onPlayPause)
                    if (index != state.books.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
                }
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
private fun DownloadedBookRow(row: ReadyBookRow, onOpenBook: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit) {
    val compact = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp)) {
            val coverSize = if (compact) ROW_COVER_LARGE_TYPE else ROW_COVER
            Box(Modifier.width(coverSize).clickable(role = Role.Button) { onOpenBook(row.face.key) }.semantics { contentDescription = "Open ${row.face.title}" }) { BookCover(row.face, coverSize, showRibbon = row.hasPlace) }
            Column(Modifier.weight(1f)) {
                Text(row.face.title, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                row.face.author?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author) }
                when (val badge = row.badge) {
                    ReadyBadge.New -> Text("New", Modifier.padding(top = 4.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f), RoundedCornerShape(100)).padding(horizontal = 10.dp, vertical = 3.dp), color = PagewornTheme.colors.accent, style = PagewornTheme.type.status)
                    is ReadyBadge.InProgress -> Text(badge.text, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                    ReadyBadge.Finished -> Text("Finished", color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                    is ReadyBadge.RepairNeeded -> { Text(Copy.REPAIR_NEEDED, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status); if (badge.message.isNotBlank()) Text(badge.message, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
                }
            }
            if (!compact && row.playable) PlayPauseKey(row.showsPause, row.face.title, ROW_KEY, starting = row.starting, start = row.start) { onPlayPause(row.face.key, it) }
        }
        if (compact && row.playable) PlayPauseKey(row.showsPause, row.face.title, ROW_KEY, starting = row.starting, start = row.start) { onPlayPause(row.face.key, it) }
    }
}

/** Lines a transfer's actions up under its title: the cover and gap less the action's own padding. */
private val ACTIONS_INDENT = ROW_COVER + 14.dp - 8.dp
