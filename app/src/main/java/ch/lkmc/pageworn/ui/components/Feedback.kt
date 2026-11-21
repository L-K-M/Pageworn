package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.model.EmptyState
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.StatusLine
import ch.lkmc.pageworn.ui.model.TransferPhase
import ch.lkmc.pageworn.ui.model.TransferUi
import ch.lkmc.pageworn.ui.model.UiAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun TextAction(label: String, spokenLabel: String? = null, onClick: () -> Unit) {
    // A short visible label can carry a fuller spoken name, e.g. "Cancel" read as "Cancel download of <title>".
    val spoken = if (spokenLabel == null) Modifier else Modifier.semantics { contentDescription = spokenLabel }
    val visible = if (spokenLabel == null) Modifier else Modifier.clearAndSetSemantics {}
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).then(spoken).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, visible, color = PagewornTheme.colors.accent, style = PagewornTheme.type.metadata)
    }
}

@Composable
fun StatusLineView(line: StatusLine, onAction: (UiAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (line.showsProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(line.text, Modifier.weight(1f).padding(start = if (line.showsProgress) 8.dp else 0.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
        }
        if (line.action != null && line.actionLabel != null) TextAction(line.actionLabel) { onAction(line.action) }
    }
}

@Composable
fun EmptyStateView(state: EmptyState, onAction: (UiAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text(state.text, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.empty)
        if (state.action != null && state.actionLabel != null) TextAction(state.actionLabel) { onAction(state.action) }
    }
}

@Composable
fun TransferIndicator(transfer: TransferUi, compact: Boolean) {
    val colors = PagewornTheme.colors
    val value = transfer.fraction
    val active = transfer.phase == TransferPhase.TRANSFERRING
    val progress = if (value != null) Format.percent(value) else Format.bytes(transfer.bytesDone)
    val semantics = Modifier.semantics {
        stateDescription = "${transfer.stateText}, $progress"
        if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
    }
    if (compact) {
        // The ring holds the progress; the row's metadata line carries the state text. At large
        // text sizes the ring grows around its label instead of the label overflowing it.
        Box(semantics.defaultMinSize(48.dp, 48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.defaultMinSize(42.dp, 42.dp).squareAroundContent(), contentAlignment = Alignment.Center) {
                val ring = Modifier.matchParentSize()
                if (active && value == null) CircularProgressIndicator(ring, strokeWidth = 3.dp)
                else CircularProgressIndicator(progress = { value ?: 0f }, modifier = ring, strokeWidth = 3.dp, color = colors.accent)
                Text(progress, Modifier.padding(6.dp), color = colors.primaryInk, style = PagewornTheme.type.metadata, maxLines = 1)
            }
        }
    } else {
        Column(semantics.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (active && value == null) LinearProgressIndicator(Modifier.weight(1f))
                else LinearProgressIndicator(progress = { value ?: 0f }, modifier = Modifier.weight(1f))
                Text(progress, Modifier.padding(start = 8.dp), color = colors.primaryInk, style = PagewornTheme.type.metadata)
            }
            Text(transfer.stateText, color = colors.secondaryInk, style = PagewornTheme.type.status)
            transfer.message?.let { Text(it, color = colors.secondaryInk, style = PagewornTheme.type.status) }
        }
    }
}

/** Gives the content a square as wide as its natural longer side, so a ring drawn in it stays round. */
private fun Modifier.squareAroundContent(): Modifier = layout { measurable, constraints ->
    val width = measurable.maxIntrinsicWidth(constraints.maxHeight)
    val side = maxOf(width, measurable.minIntrinsicHeight(width), constraints.minWidth, constraints.minHeight)
        .coerceAtMost(minOf(constraints.maxWidth, constraints.maxHeight))
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}
