package ch.lkmc.pageworn.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.model.MiniPlayerUi
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun MiniPlayer(state: MiniPlayerUi, onOpen: () -> Unit, onPlayPause: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(PagewornTheme.colors.raisedSheet)) {
        HorizontalDivider(color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpen).semantics { contentDescription = "Open player, ${state.face.title}" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(state.face, 48.dp)
                Box(Modifier.size(1.dp, 40.dp).background(PagewornTheme.colors.secondaryInk.copy(alpha = .3f)))
                Column(Modifier.weight(1f)) {
                    Text(state.face.title, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle, maxLines = 2)
                    state.subtitle?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata, maxLines = 2) }
                }
            }
            PlayPauseKey(state.isPlaying, state.face.title, 36.dp, enabled = state.enabled, disabledReason = state.disabledReason, start = state.start, onClick = onPlayPause)
        }
    }
}
