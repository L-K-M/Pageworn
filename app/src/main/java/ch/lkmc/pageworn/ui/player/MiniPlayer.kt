package ch.lkmc.pageworn.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.ROW_KEY
import ch.lkmc.pageworn.ui.components.rememberTapSound
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.MiniPlayerUi
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun MiniPlayer(state: MiniPlayerUi, onOpen: () -> Unit, onPlayPause: (KeyAction) -> Unit) {
    // One line each for title and chapter, so that the list keeps the screen. At large text the cover
    // gives its width to the words.
    val showsCover = LocalDensity.current.fontScale < 1.5f
    val sound = rememberTapSound()
    Column(Modifier.fillMaxWidth().background(PagewornTheme.colors.raisedSheet)) {
        HorizontalDivider(color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { sound(); onOpen() }
                    .semantics { contentDescription = "Open player, ${state.face.title}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showsCover) {
                    BookCover(state.face, 44.dp)
                    Box(Modifier.size(1.dp, 32.dp).background(PagewornTheme.colors.secondaryInk.copy(alpha = .3f)))
                }
                Column(Modifier.weight(1f)) {
                    Text(state.face.title, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.subtitle?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            PlayPauseKey(state.showsPause, state.face.title, ROW_KEY, starting = state.starting, enabled = state.enabled, disabledReason = state.disabledReason, start = state.start, onClick = onPlayPause)
        }
    }
}
