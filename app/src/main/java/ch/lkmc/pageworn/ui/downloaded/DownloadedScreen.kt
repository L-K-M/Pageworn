package ch.lkmc.pageworn.ui.downloaded

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.CoverCorner
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
import ch.lkmc.pageworn.ui.components.feedbackClickable
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.DownloadedUiState
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.ReadyBadge
import ch.lkmc.pageworn.ui.model.ReadyBookRow
import ch.lkmc.pageworn.ui.model.TransferRow
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/** The page's side margin: the heading, rules and transfer rows start here, and so does the shelf. */
private val PAGE_MARGIN = 20.dp

/** Space between covers across a row, and below each row under the room its ribbons hang in. */
private val SHELF_GUTTER = 14.dp
private val SHELF_ROW_GAP = 10.dp

/** Room between the rule above the shelf and its first row of covers. */
private val SHELF_TOP = 16.dp

/**
 * The play key on a cover: a smaller face than a row's, in the same 48 dp target. Its centre sits
 * this far in from the book's lower corner, so the rim rests just past the corner and the key hides
 * as little of the art and its lettering as it can.
 */
private val COVER_KEY = 30.dp
private val COVER_KEY_TARGET = 48.dp
private val COVER_KEY_CENTRE_INSET = 11.dp

/** How far the key's target, and its visible face, reach in from the book's right and lower edges. */
private val COVER_KEY_REACH = COVER_KEY_CENTRE_INSET + COVER_KEY_TARGET / 2
private val COVER_KEY_FACE_REACH = COVER_KEY_CENTRE_INSET + COVER_KEY / 2

/**
 * The narrowest cover before the grid gives the shelf one column fewer. It keeps covers book-sized,
 * and wider than twice the key's reach: a narrower cover would let the key's target reach its
 * centre, and a tap on the cover would start the book instead of opening it.
 */
private val SHELF_COVER_MIN = 84.dp

/** A damaged copy's cover is faded, as something that cannot be played until it is repaired. */
private const val DAMAGED_COVER_ALPHA = 0.5f

/** A heading, a status line and a transfer take the grid's whole width; only the books share rows. */
private fun LazyGridItemSpanScope.fullWidth() = GridItemSpan(maxLineSpan)

/**
 * Lets a full-width item run under the shelf's side margins. The heading, the rules, the status line
 * and the transfer rows keep their own insets from the screen's edges, as on every other page.
 */
private fun Modifier.acrossMargins(): Modifier = layout { measurable, constraints ->
    val margin = PAGE_MARGIN.roundToPx()
    val width = constraints.maxWidth + margin * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-margin, 0) }
}

@Composable
fun DownloadedScreen(state: DownloadedUiState, listState: LazyGridState, onOpenBook: (BookKey) -> Unit, onPlayPause: (BookKey, KeyAction) -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onRetryTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit, onAction: (UiAction) -> Unit, onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    val layoutDirection = LocalLayoutDirection.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = SHELF_COVER_MIN),
        state = listState,
        // The shelf keeps the page's margins; the full-width items reach back under them.
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + PAGE_MARGIN,
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection) + PAGE_MARGIN,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(SHELF_GUTTER),
    ) {
        item(key = "heading", span = { fullWidth() }) {
            Column(Modifier.acrossMargins()) {
                SectionHeader(state.heading, onOpenSettings)
                PaperRule()
            }
        }
        state.status?.let { item(key = "status", span = { fullWidth() }) { Box(Modifier.acrossMargins()) { StatusLineView(it, onAction) } } }
        if (state.empty != null) item(key = "empty", span = { fullWidth() }) { Box(Modifier.acrossMargins()) { EmptyStateView(state.empty, onAction) } }
        else {
            if (state.transfers.isNotEmpty()) item(key = "downloading", span = { fullWidth() }) {
                // Fades with the last transfer row instead of popping out a frame early.
                Text("Downloading", Modifier.animateItem().acrossMargins().padding(start = PAGE_MARGIN, top = 12.dp, bottom = 8.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.sheetTitle.copy(fontSize = PagewornTheme.type.rowTitle.fontSize * 1.15f))
            }
            itemsIndexed(state.transfers, key = { _, row -> "transfer:${row.transfer.jobId}" }, span = { _, _ -> fullWidth() }) { index, row ->
                // A finished transfer fades out of the group above as its book fades in below.
                Column(Modifier.animateItem().acrossMargins()) {
                    DownloadTransferRow(row, onOpenBook, onPauseTransfer, onResumeTransfer, onRetryTransfer, onCancelTransfer)
                    // The divider after the last transfer separates the sections while books
                    // follow, and stays away when it is the bottom of the list.
                    if (index != state.transfers.lastIndex || state.books.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = PAGE_MARGIN), color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
                }
            }
            if (state.books.isNotEmpty()) item(key = "shelf", span = { fullWidth() }) { Spacer(Modifier.height(SHELF_TOP)) }
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
            Box(Modifier.width(coverSize).feedbackClickable(Role.Button) { onOpenBook(row.face.key) }.semantics { contentDescription = "Open ${row.face.title}" }) { BookCover(row.face, coverSize) }
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
    BoxWithConstraints(Modifier.animateItem().padding(bottom = SHELF_ROW_GAP)) {
        val cover = maxWidth
        val repair = row.badge as? ReadyBadge.RepairNeeded
        Column {
            // The ribbon hangs below the art. Holding its space whether or not the book has a saved
            // place keeps the cover, its key and the shelf around them still when one is saved.
            Box(Modifier.size(cover, cover + ribbonSpace(cover))) {
                BookCover(
                    row.face, cover, showRibbon = row.hasPlace,
                    modifier = Modifier
                        .then(if (repair != null) Modifier.alpha(DAMAGED_COVER_ALPHA) else Modifier)
                        .feedbackClickable(Role.Button) { onOpenBook(row.face.key) }
                        .semantics {
                            contentDescription = "Open ${row.face.title}"
                            stateDescription = row.spokenState()
                        },
                    corner = if (row.playable) CoverCorner(COVER_KEY_FACE_REACH) { CoverKey(row, onPlayPause) } else null,
                )
            }
            // A damaged copy is the one state a cover cannot show, and the only one that stops the
            // book from playing, so it says so under the art. Details say why and offer the repair.
            if (repair != null) Text(Copy.REPAIR_NEEDED, Modifier.fillMaxWidth(), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The play key over a cover's lower corner. On narrow art its target would reach the book's centre,
 * so the key moves sideways past the corner just far enough to leave the centre clear. A target
 * clear of the centre's column is clear of the centre, so short art needs no move: the key keeps
 * to its cell and the row below.
 */
@Composable
private fun BoxScope.CoverKey(row: ReadyBookRow, onPlayPause: (BookKey, KeyAction) -> Unit) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val outward = COVER_KEY_TARGET / 2 - COVER_KEY_CENTRE_INSET
        val clear = (COVER_KEY_REACH - maxWidth / 2 + 1.dp).coerceAtLeast(0.dp)
        Box(Modifier.align(Alignment.BottomEnd).offset(x = outward + clear, y = outward)) {
            PlayPauseKey(row.showsPause, row.face.title, COVER_KEY, starting = row.starting, start = row.start) { onPlayPause(row.face.key, it) }
        }
    }
}

/**
 * What a cover alone does not say, for TalkBack: the local state the shelf used to print under a
 * book's title. A damaged copy gives its reason, which the shelf no longer prints.
 */
private fun ReadyBookRow.spokenState(): String = when (val badge = badge) {
    ReadyBadge.New -> "New"
    ReadyBadge.Finished -> "Finished"
    is ReadyBadge.InProgress -> badge.text
    is ReadyBadge.RepairNeeded -> badge.message.ifBlank { Copy.REPAIR_NEEDED }
}

/** Lines a transfer's actions up under its title: the cover and gap less the action's own padding. */
private val ACTIONS_INDENT = ROW_COVER + 14.dp - 8.dp
