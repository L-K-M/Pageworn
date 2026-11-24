package ch.lkmc.pageworn.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.BackAction
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.PaperButton
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.TransferIndicator
import ch.lkmc.pageworn.ui.components.TransferPauseKey
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

/**
 * The ribbon marks a saved place, so it belongs only to a book that resumes from one. A fresh
 * play and a finished replay both start at zero, so they deliberately show no ribbon.
 */
internal fun showsPlaceRibbon(action: DetailAction): Boolean =
    (action as? DetailAction.Play)?.start == StartAction.RESUME

@Composable
fun BookDetailScreen(state: BookDetailUiState, onBack: () -> Unit, onDownload: () -> Unit, onRetry: () -> Unit, onPlayPause: (KeyAction) -> Unit, onRepair: () -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit, onSelectSegment: (Long) -> Unit, onRemove: () -> Unit) {
    // One lazy list: a long part list composes only the rows near the viewport, instead of paying
    // for every row before the cover and primary action can appear. Chapter keys keep each row's
    // identity while the list scrolls.
    LazyColumn(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item(key = "back") { Row(Modifier.fillMaxWidth()) { BackAction(onBack) } }
        item(key = "header") {
            BookCover(state.face, 168.dp, showRibbon = showsPlaceRibbon(state.action))
            Text(state.face.title, Modifier.padding(top = 20.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.playerTitle, textAlign = TextAlign.Center)
            state.face.author?.let { Text(it, Modifier.padding(top = 2.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author, textAlign = TextAlign.Center) }
            PaperRule(Modifier.fillMaxWidth(.78f).padding(vertical = 10.dp))
            state.narrator?.let { Text("Narrated by $it", color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
            val facts = listOfNotNull(state.durationText, state.sizeText).joinToString(" · ")
            if (facts.isNotEmpty()) Text(facts, Modifier.padding(top = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
        }
        item(key = "action") {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (val action = state.action) {
                    DetailAction.Download -> PaperButton(Copy.DOWNLOAD, onDownload, Modifier.semantics { contentDescription = "Download ${state.face.title}" })
                    DetailAction.DownloadDisabled, DetailAction.Unsupported -> {
                        PaperButton(Copy.DOWNLOAD, {}, Modifier.semantics { contentDescription = "Download ${state.face.title}"; stateDescription = state.message ?: if (action == DetailAction.Unsupported) Copy.UNSUPPORTED_FORMAT else Copy.DOWNLOAD_DISABLED }, enabled = false)
                        TextAction(Copy.RETRY, spokenLabel = "Retry download of ${state.face.title}", onClick = onRetry)
                    }
                    is DetailAction.Play -> PlayPauseKey(action.showsPause, state.face.title, 68.dp, starting = action.starting, start = action.start, onClick = onPlayPause)
                    DetailAction.Repair -> PaperButton(Copy.REPAIR_DOWNLOAD, onRepair, Modifier.semantics { contentDescription = "Repair download of ${state.face.title}" })
                    is DetailAction.Transferring -> {
                        val t = action.transfer
                        TransferIndicator(t, compact = false)
                        if (t.canPause) TransferPauseKey(state.face.title) { onPauseTransfer(t.jobId) }
                        if (t.canResume) TextAction("Resume", spokenLabel = "Resume download of ${state.face.title}", onClick = { onResumeTransfer(t.jobId) })
                        if (t.canCancel) TextAction(Copy.CANCEL, spokenLabel = "Cancel download of ${state.face.title}", onClick = { onCancelTransfer(t.jobId) })
                    }
                }
                val message = state.message ?: when (state.action) {
                    DetailAction.DownloadDisabled -> Copy.DOWNLOAD_DISABLED
                    DetailAction.Unsupported -> Copy.UNSUPPORTED_FORMAT
                    else -> null
                }
                message?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
            }
        }
        state.description?.let { description ->
            item(key = "description") {
                Text(description, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.empty)
            }
        }
        state.segmentsTitle?.let { title ->
            item(key = "segments-title") {
                Text(title, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.sheetTitle)
            }
            items(state.segments, key = { it.index }) { segment ->
                PaperRule()
                Row(Modifier.fillMaxWidth().then(if (state.segmentsPlayable) Modifier.clickable(role = Role.Button) { onSelectSegment(segment.startMs) }.semantics { contentDescription = "Play ${segment.title} in ${state.face.title}" } else Modifier).padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(segment.title, Modifier.weight(1f), color = if (segment.generated) PagewornTheme.colors.secondaryInk else PagewornTheme.colors.primaryInk, style = PagewornTheme.type.author)
                    Text(segment.durationText, Modifier.padding(start = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
                }
            }
        }
        if (state.canRemove) item(key = "removal") {
            TextAction("Remove download", spokenLabel = "Remove download of ${state.face.title}", onClick = onRemove)
        }
    }
}
