package ch.lkmc.pageworn.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.TransferIndicator
import ch.lkmc.pageworn.ui.components.TransferPauseKey
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun BookDetailScreen(state: BookDetailUiState, onBack: () -> Unit, onDownload: () -> Unit, onPlayPause: () -> Unit, onRepair: () -> Unit, onPauseTransfer: (String) -> Unit, onResumeTransfer: (String) -> Unit, onCancelTransfer: (String) -> Unit, onSelectSegment: (Long) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) { TextAction("Back", onClick = onBack) }
        BookCover(state.face, 180.dp, showRibbon = state.action is DetailAction.Play)
        Text(state.face.title, Modifier.padding(top = 20.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.playerTitle)
        state.face.author?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.rowTitle) }
        state.narrator?.let { Text("Narrated by $it", color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
        val facts = listOfNotNull(state.durationText, state.sizeText).joinToString(" · ")
        if (facts.isNotEmpty()) Text(facts, Modifier.padding(top = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val action = state.action) {
                DetailAction.Download -> Button(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Download ${state.face.title}" }) { Text(Copy.DOWNLOAD) }
                DetailAction.DownloadDisabled, DetailAction.Unsupported -> Button(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Download ${state.face.title}"; stateDescription = state.message ?: if (action == DetailAction.Unsupported) Copy.UNSUPPORTED_FORMAT else Copy.DOWNLOAD_DISABLED }) { Text(Copy.DOWNLOAD) }
                is DetailAction.Play -> PlayPauseKey(action.isPlaying, state.face.title, 72.dp, start = action.start, onClick = onPlayPause)
                DetailAction.Repair -> Button(onClick = onRepair, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Repair download of ${state.face.title}" }) { Text(Copy.REPAIR_DOWNLOAD) }
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
        state.description?.let { Text(it, Modifier.fillMaxWidth().padding(vertical = 12.dp), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.empty) }
        state.segmentsTitle?.let { title ->
            Text(title, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.playerTitle)
            state.segments.forEach { segment ->
                HorizontalDivider(color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
                Row(Modifier.fillMaxWidth().then(if (state.segmentsPlayable) Modifier.clickable(role = Role.Button) { onSelectSegment(segment.startMs) }.semantics { contentDescription = "Play ${segment.title} in ${state.face.title}" } else Modifier).padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(segment.title, Modifier.weight(1f), color = if (segment.generated) PagewornTheme.colors.secondaryInk else PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                    Text(segment.durationText, Modifier.padding(start = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
                }
            }
        }
        if (state.canRemove) TextAction("Remove download", spokenLabel = "Remove download of ${state.face.title}", onClick = onRemove)
    }
}
