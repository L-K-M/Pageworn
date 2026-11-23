package ch.lkmc.pageworn.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.core.timeline.Segment
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PaperSheet
import ch.lkmc.pageworn.ui.components.PencilIcon
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.RadioRow
import ch.lkmc.pageworn.ui.components.SkipKey
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.PlayerUiState
import ch.lkmc.pageworn.ui.state.PlayerStates
import ch.lkmc.pageworn.ui.model.SleepUi
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

private enum class PlayerSheet { SPEED, SEGMENTS, SLEEP }

private const val ROWS_ABOVE_CURRENT = 2
private val UTILITY_SHAPE = RoundedCornerShape(12.dp)
private val UTILITY_ICON = 28.dp

/** Preview hook for the documented night sheet; production starts with all sheets closed. */
val LocalPreviewSleepSheet = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(state: PlayerUiState, onCollapse: () -> Unit, onPlayPause: (KeyAction) -> Unit, onSkipBack: () -> Unit, onSkipForward: () -> Unit, onSeekTo: (Long) -> Unit, onSelectSpeed: (Float) -> Unit, onSelectSegment: (Long) -> Unit, onSelectSleep: (SleepLength?) -> Unit, onRepair: () -> Unit, onUsePreviousPosition: () -> Unit, onStartOver: () -> Unit) {
    val previewSleep = LocalPreviewSleepSheet.current
    var sheet by rememberSaveable(state.face.key.mediaId) { mutableStateOf<PlayerSheet?>(if (previewSleep) PlayerSheet.SLEEP else null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BoxWithConstraints(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding()) {
        val short = maxHeight < 700.dp
        val narrow = maxWidth < 360.dp
        val coverSize = if (short) 136.dp else minOf(maxWidth * .5f, 200.dp)
        val playSize = if (short || narrow) 68.dp else 80.dp
        val skipWidth = if (narrow) 66.dp else 80.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                androidx.compose.material3.IconButton(onClick = onCollapse, modifier = Modifier.heightIn(min = 48.dp)) { PencilIcon(R.drawable.pw_pencil_collapse, "Collapse player", 24.dp) }
            }
            BookCover(state.face, coverSize, showRibbon = state.hasPlace)
            Text(state.face.title, Modifier.padding(top = if (short) 10.dp else 16.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.playerTitle, textAlign = TextAlign.Center)
            state.face.author?.let { Text(it, Modifier.padding(top = 2.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author.copy(fontSize = PagewornTheme.type.rowTitle.fontSize), textAlign = TextAlign.Center) }
            state.failureMessage?.let {
                Text(it, Modifier.padding(top = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                TextAction(Copy.REPAIR_DOWNLOAD, onClick = onRepair)
            }
            state.playNotice?.let {
                Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
            }
            state.continuityChoice?.let {
                Text("Previous position: ${it.previousPositionText} · New duration: ${it.newDurationText}", Modifier.padding(top = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                TextAction(Copy.USE_PREVIOUS_POSITION, onClick = onUsePreviousPosition)
                TextAction(Copy.START_OVER, onClick = onStartOver)
            }
            PaperRule(Modifier.fillMaxWidth(.78f).padding(top = 12.dp, bottom = 10.dp))
            Text(state.segmentLine, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.author, textAlign = TextAlign.Center)
            PlayerScrubbers(state, onSeekTo, Modifier.padding(top = 4.dp))
            // Spare height opens up around the keys, as on the drawn player.
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipKey(back = true, width = skipWidth, onClick = onSkipBack, enabled = state.transportEnabled, disabledReason = state.transportDisabledReason)
                PlayPauseKey(state.showsPause, state.face.title, playSize, starting = state.starting, enabled = state.transportEnabled, disabledReason = state.transportDisabledReason, start = StartAction.of(state.hasPlace, state.finished), onClick = onPlayPause)
                SkipKey(back = false, width = skipWidth, onClick = onSkipForward, enabled = state.transportEnabled, disabledReason = state.transportDisabledReason)
            }
            @Composable fun utilities() {
                Utility("Speed", "Playback speed, ${Format.spokenSpeed(state.speed)}", onClick = { sheet = PlayerSheet.SPEED }) {
                    if (state.speed == 1f) PencilIcon(R.drawable.pw_pencil_speed_1x, null, UTILITY_ICON)
                    else Text(Format.speed(state.speed), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.sheetTitle.copy(fontStyle = FontStyle.Italic))
                }
                Utility(state.segmentsLabel, state.segmentsLabel, onClick = { sheet = PlayerSheet.SEGMENTS }) { PencilIcon(R.drawable.pw_pencil_chapters, null, UTILITY_ICON) }
                Utility("Sleep", state.sleep.utilityDescription, target = state.sleep.shortTarget, onClick = { sheet = PlayerSheet.SLEEP }) { PencilIcon(R.drawable.pw_pencil_sleep, null, UTILITY_ICON) }
            }
            Spacer(Modifier.weight(1f))
            if (LocalDensity.current.fontScale >= 1.5f) Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) { utilities() }
            else Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) { utilities() }
        }
    }
    when (sheet) {
        PlayerSheet.SPEED -> PaperSheet("Speed", sheetState, { sheet = null }) { SpeedSheet(state.speed) { onSelectSpeed(it); sheet = null } }
        PlayerSheet.SEGMENTS -> PaperSheet(state.segmentsLabel, sheetState, { sheet = null }) { SegmentsSheet(state.division, state.segmentName, state.segments, state.segmentIndex) { onSelectSegment(it); sheet = null } }
        PlayerSheet.SLEEP -> PaperSheet("Sleep timer", sheetState, { sheet = null }) { SleepSheet(state.sleep) { onSelectSleep(it); sheet = null } }
        null -> Unit
    }
}

@Composable
private fun Utility(label: String, description: String, target: String? = null, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.heightIn(min = 60.dp).widthIn(min = 48.dp).clip(UTILITY_SHAPE).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Text(label, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.ribbonLabel)
        // Tabular figures keep a ticking countdown from shifting the row every second.
        target?.let { Text(it, color = PagewornTheme.colors.accent, style = PagewornTheme.type.status.copy(fontFeatureSettings = "tnum")) }
    }
}

@Composable
private fun SpeedSheet(selected: Float, onSelect: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 12.dp).selectableGroup()) {
        listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed -> RadioRow(Format.speed(speed), selected == speed, null) { onSelect(speed) } }
    }
}

@Composable
private fun SegmentsSheet(division: Division, segmentName: String, segments: List<Segment>, segmentIndex: Int, onSelect: (Long) -> Unit) {
    // A long book has dozens of parts: open on the one playing, with some before it for context.
    val list = rememberLazyListState((segmentIndex - ROWS_ABOVE_CURRENT).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), list) {
            items(segments, key = { it.index }) { segment ->
                PaperRule()
                val current = segment.index == segmentIndex
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onSelect(segment.startMs) }.semantics { if (current) stateDescription = "Current ${segmentName.lowercase()}" }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current) Image(painterResource(R.drawable.pw_place_ribbon), null, Modifier.padding(end = 8.dp).size(12.dp, 18.dp))
                    Text(PlayerStates.segmentTitle(division, segment), Modifier.weight(1f), color = if (segment.generated) PagewornTheme.colors.secondaryInk else PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                    Text(Format.clock(segment.durationMs), Modifier.padding(start = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
                }
            }
        }
    }
}

@Composable
private fun SleepSheet(sleep: SleepUi, onSelect: (SleepLength?) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 12.dp).selectableGroup()) {
        sleep.options.forEach { option ->
            val selected = sleep.selected == option.length
            RadioRow(option.label, selected, if (selected) sleep.remainingText else null) { onSelect(option.length) }
        }
    }
}
