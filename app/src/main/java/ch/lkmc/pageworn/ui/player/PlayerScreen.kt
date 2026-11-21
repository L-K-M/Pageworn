package ch.lkmc.pageworn.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.components.BookCover
import ch.lkmc.pageworn.ui.components.PencilIcon
import ch.lkmc.pageworn.ui.components.PlayPauseKey
import ch.lkmc.pageworn.ui.components.SkipKey
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperBackground
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.PlayerUiState
import ch.lkmc.pageworn.ui.model.SegmentRow
import ch.lkmc.pageworn.ui.model.SleepChoice
import ch.lkmc.pageworn.ui.model.SleepUi
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme

private enum class PlayerSheet { SPEED, SEGMENTS, SLEEP }
/** Preview hook for the documented night sheet; production starts with all sheets closed. */
val LocalPreviewSleepSheet = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(state: PlayerUiState, onCollapse: () -> Unit, onPlayPause: () -> Unit, onSkipBack: () -> Unit, onSkipForward: () -> Unit, onSeekTo: (Long) -> Unit, onSelectSpeed: (Float) -> Unit, onSelectSegment: (Long) -> Unit, onSelectSleep: (SleepChoice) -> Unit, onRepair: () -> Unit, onUsePreviousPosition: () -> Unit, onStartOver: () -> Unit) {
    val previewSleep = LocalPreviewSleepSheet.current
    var sheet by rememberSaveable(state.face.key.mediaId) { mutableStateOf<PlayerSheet?>(if (previewSleep) PlayerSheet.SLEEP else null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BoxWithConstraints(Modifier.fillMaxSize().paperBackground().statusBarsPadding().navigationBarsPadding()) {
        val coverSize = if (maxHeight < 700.dp) 160.dp else 208.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                androidx.compose.material3.IconButton(onClick = onCollapse, modifier = Modifier.heightIn(min = 48.dp)) { PencilIcon(R.drawable.pw_pencil_collapse, "Collapse player") }
            }
            BookCover(state.face, coverSize, showRibbon = state.hasPlace)
            Text(state.face.title, Modifier.padding(top = 22.dp).semantics { heading() }, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.playerTitle)
            state.face.author?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.rowTitle) }
            state.failureMessage?.let {
                Text(it, Modifier.padding(top = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                TextAction(Copy.REPAIR_DOWNLOAD, onClick = onRepair)
            }
            state.continuityChoice?.let {
                Text("Previous position: ${it.previousPositionText} · New duration: ${it.newDurationText}", Modifier.padding(top = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
                TextAction(Copy.USE_PREVIOUS_POSITION, onClick = onUsePreviousPosition)
                TextAction(Copy.START_OVER, onClick = onStartOver)
            }
            HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 10.dp), color = PagewornTheme.colors.secondaryInk.copy(alpha = .35f))
            state.segmentLine?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.rowTitle) }
            val duration = (state.segmentEndMs - state.segmentStartMs).coerceAtLeast(0L)
            val position = (state.positionMs - state.segmentStartMs).coerceIn(0, duration)
            var fraction by remember(state.face.key, state.segmentStartMs, state.segmentEndMs) { mutableFloatStateOf(if (duration > 0) position.toFloat() / duration else 0f) }
            var dragging by remember(state.face.key) { mutableStateOf(false) }
            LaunchedEffect(state.positionMs, state.segmentStartMs, state.segmentEndMs, dragging) {
                if (!dragging) fraction = if (duration > 0) position.toFloat() / duration else 0f
            }
            Slider(
                value = fraction,
                onValueChange = { dragging = true; fraction = it },
                onValueChangeFinished = { onSeekTo(state.segmentStartMs + (fraction * duration).toLong()); dragging = false },
                enabled = duration > 0 && state.transportEnabled,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics {
                    stateDescription = state.elapsedText
                    customActions = if (state.transportEnabled) listOf(
                        CustomAccessibilityAction("Back 15 seconds") { onSeekTo((state.positionMs - 15_000).coerceAtLeast(state.segmentStartMs)); true },
                        CustomAccessibilityAction("Forward 30 seconds") { onSeekTo((state.positionMs + 30_000).coerceAtMost(state.segmentEndMs)); true },
                    ) else emptyList()
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.elapsedText, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.time)
                Text(state.remainingText, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.time)
            }
            state.bookLine?.let { Text(it, Modifier.padding(top = 12.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata) }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                SkipKey(back = true, onClick = onSkipBack, enabled = state.transportEnabled, disabledReason = state.failureMessage)
                PlayPauseKey(state.isPlaying, state.face.title, 72.dp, enabled = state.transportEnabled, disabledReason = state.failureMessage, start = StartAction.of(state.hasPlace, state.finished), onClick = onPlayPause)
                SkipKey(back = false, onClick = onSkipForward, enabled = state.transportEnabled, disabledReason = state.failureMessage)
            }
            @Composable fun utilities() {
                Utility("Speed", "Playback speed, ${Format.spokenSpeed(state.speed)}", onClick = { sheet = PlayerSheet.SPEED }) {
                    if (state.speed == 1f) PencilIcon(R.drawable.pw_pencil_speed_1x, null, 28.dp)
                    else Text(Format.speed(state.speed), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.playerTitle.copy(fontStyle = FontStyle.Italic))
                }
                Utility(state.segmentsLabel, state.segmentsLabel, enabled = state.segmentsEnabled, disabledReason = state.segmentsDisabledReason ?: Copy.NO_CHAPTERS, onClick = { sheet = PlayerSheet.SEGMENTS }) { PencilIcon(R.drawable.pw_pencil_chapters, null, 28.dp) }
                Utility("Sleep", state.sleep.utilityDescription, onClick = { sheet = PlayerSheet.SLEEP }) { PencilIcon(R.drawable.pw_pencil_sleep, null, 28.dp) }
            }
            if (LocalDensity.current.fontScale >= 1.5f) Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) { utilities() }
            else Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) { utilities() }
        }
    }
    when (sheet) {
        PlayerSheet.SPEED -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) { SpeedSheet(state.speed) { onSelectSpeed(it); sheet = null } }
        PlayerSheet.SEGMENTS -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) { SegmentsSheet(state.segmentsLabel, state.segments) { onSelectSegment(it); sheet = null } }
        PlayerSheet.SLEEP -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = sheetState) { SleepSheet(state.sleep) { onSelectSleep(it); sheet = null } }
        null -> Unit
    }
}

@Composable
private fun Utility(label: String, description: String, enabled: Boolean = true, disabledReason: String? = null, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(Modifier.heightIn(min = 72.dp).widthIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description; if (!enabled) { disabled(); disabledReason?.let { stateDescription = it } } }.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Text(label, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.ribbonLabel)
    }
}

@Composable
private fun SpeedSheet(selected: Float, onSelect: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).selectableGroup()) {
        Text("Speed", Modifier.padding(bottom = 12.dp).semantics { heading() }, style = PagewornTheme.type.heading)
        listOf(.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed -> RadioRow(Format.speed(speed), selected == speed, null) { onSelect(speed) } }
    }
}

@Composable
private fun SegmentsSheet(title: String, segments: List<SegmentRow>, onSelect: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Text(title, Modifier.padding(bottom = 12.dp).semantics { heading() }, style = PagewornTheme.type.heading)
        segments.forEach { segment ->
            HorizontalDivider(color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onSelect(segment.startMs) }.semantics { if (segment.current) stateDescription = "Current chapter" }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (segment.current) Image(painterResource(R.drawable.pw_place_ribbon), null, Modifier.padding(end = 8.dp).size(12.dp, 18.dp))
                Text(segment.title, Modifier.weight(1f), color = if (segment.generated) PagewornTheme.colors.secondaryInk else PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                Text(segment.durationText, Modifier.padding(start = 8.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.metadata)
            }
        }
    }
}

@Composable
private fun SleepSheet(sleep: SleepUi, onSelect: (SleepChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).selectableGroup()) {
        Text("Sleep timer", Modifier.padding(bottom = 12.dp).semantics { heading() }, style = PagewornTheme.type.heading)
        listOf(
            SleepChoice.OFF to "Off", SleepChoice.MINUTES_15 to "15 minutes", SleepChoice.MINUTES_30 to "30 minutes",
            SleepChoice.MINUTES_45 to "45 minutes", SleepChoice.MINUTES_60 to "60 minutes", SleepChoice.END_OF_SEGMENT to sleep.endLabel,
        ).forEach { (choice, label) -> RadioRow(label, sleep.selected == choice, if (sleep.selected == choice) sleep.remainingText else null) { onSelect(choice) } }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, detail: String?, onClick: () -> Unit) {
    HorizontalDivider(color = PagewornTheme.colors.secondaryInk.copy(alpha = .25f))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
            detail?.let { Text(it, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status) }
        }
        RadioButton(selected = selected, onClick = null)
    }
}
