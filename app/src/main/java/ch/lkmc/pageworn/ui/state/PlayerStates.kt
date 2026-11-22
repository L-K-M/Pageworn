package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.playback.SleepState
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.ContinuityChoice
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.MiniPlayerUi
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.model.PlayerUiState
import ch.lkmc.pageworn.ui.model.SegmentRow
import ch.lkmc.pageworn.ui.model.SleepChoice
import ch.lkmc.pageworn.ui.model.SleepUi

/** Builds the player and mini-player state of one book from its edition, progress and the live player. */
object PlayerStates {

    fun endLabel(division: Division) = when (division) {
        Division.CHAPTERS -> "End of chapter"
        Division.TRACKS -> "End of track"
        Division.WHOLE_BOOK -> "End of book"
    }

    fun segmentTitle(plan: EditionPlan, index: Int): String {
        val s = plan.timeline.segments[index]
        return when (plan.timeline.division) {
            Division.CHAPTERS -> s.title ?: "Chapter ${index + 1}"
            Division.TRACKS -> s.title?.takeIf { it.isNotBlank() } ?: "Track ${index + 1}"
            Division.WHOLE_BOOK -> plan.title
        }
    }

    fun segments(plan: EditionPlan, positionMs: Long?): List<SegmentRow> {
        if (plan.timeline.division == Division.WHOLE_BOOK) return emptyList()
        val current = positionMs?.let { plan.timeline.segmentIndexAt(it) }
        return plan.timeline.segments.mapIndexed { i, s ->
            SegmentRow(i, segmentTitle(plan, i), Format.clock(s.durationMs), s.generated, current == i, s.startMs)
        }
    }

    fun face(plan: EditionPlan) =
        BookFace(plan.key, plan.title, plan.author, plan.coverFile?.let { CoverImage.Local(it) } ?: CoverImage.Cloth)

    fun sleep(live: LivePlayback, division: Division): SleepUi {
        val label = endLabel(division)
        val (choice, description) = when (val s = live.sleep) {
            SleepState.Off -> SleepChoice.OFF to "Sleep, off"
            is SleepState.Timed -> when (s.minutes) {
                15 -> SleepChoice.MINUTES_15
                30 -> SleepChoice.MINUTES_30
                45 -> SleepChoice.MINUTES_45
                else -> SleepChoice.MINUTES_60
            } to "Sleep, ${Format.spokenDuration(live.sleepRemainingMs ?: 0)} remaining"
            is SleepState.EndOf -> SleepChoice.END_OF_SEGMENT to "Sleep, ${label.lowercase()}"
        }
        return SleepUi(choice, label, live.sleepRemainingMs?.let { Format.sleepRemaining(it) }, description)
    }

    /**
     * The live player failed on the edition that is still published. A repair publishes another
     * generation, which the next play loads, so the failure no longer disables its keys.
     */
    private fun liveFailure(plan: EditionPlan, edition: LocalEditionEntity?, live: LivePlayback): Boolean =
        live.key == plan.key && live.failed && (edition == null || live.plan?.generationId == edition.generationId)

    /**
     * Why the keys are disabled, if they are. Audio that stopped in this session says so; any other
     * edition that needs repair (after a relaunch, or found damaged before opening) is a damaged
     * copy, as its rows say.
     */
    private fun failureReason(plan: EditionPlan, edition: LocalEditionEntity?, live: LivePlayback): String? = when {
        liveFailure(plan, edition, live) -> Copy.PLAYBACK_STOPPED
        edition?.state == EditionState.REPAIR_NEEDED -> Copy.DAMAGED_COPY
        else -> null
    }

    /**
     * The player for [plan]. When the live player holds this book its position and state win;
     * otherwise the saved place is shown, as Downloaded and the ribbon show it while the book rests.
     */
    fun player(
        plan: EditionPlan,
        edition: LocalEditionEntity?,
        progress: BookProgressEntity?,
        live: LivePlayback,
    ): PlayerUiState {
        val timeline = plan.timeline
        val isLive = live.key == plan.key
        val finished = progress?.finished == true && !live.showsPause(plan.key)
        val position = timeline.clamp(if (isLive) live.positionMs else progress?.positionMs ?: 0L)
        val segment = timeline.segmentAt(position)
        val whole = timeline.division == Division.WHOLE_BOOK
        val failure = failureReason(plan, edition, live)
        val failed = failure != null
        val showsPause = live.showsPause(plan.key)
        // Until the listener has chosen where changed audio starts, there is no place to play from.
        val choiceNeeded = progress?.pendingContinuityChoice == true && !showsPause
        return PlayerUiState(
            face = face(plan),
            narrator = edition?.narrators,
            segmentLine = if (whole) null else segmentTitle(plan, segment.index),
            segmentStartMs = segment.startMs,
            segmentEndMs = segment.endMs,
            positionMs = position,
            elapsedText = Format.clock(position - segment.startMs),
            remainingText = Format.remaining(segment.endMs - position),
            bookLine = if (whole) null else Format.bookLine(position, timeline.durationMs),
            showsPause = showsPause,
            starting = live.waitsForAudio(plan.key),
            transportEnabled = !failed && !choiceNeeded,
            transportDisabledReason = failure ?: Copy.CHOOSE_START.takeIf { choiceNeeded },
            failureMessage = failure,
            speed = live.speed,
            sleep = sleep(live, timeline.division),
            segmentsLabel = if (timeline.division == Division.TRACKS) "Tracks" else "Chapters",
            segmentsEnabled = !whole,
            segmentsDisabledReason = if (whole) Copy.NO_CHAPTERS else null,
            segments = segments(plan, position),
            hasPlace = !finished && progress != null && (progress.positionMs > 0 || isLive),
            finished = finished,
            continuityChoice = progress?.takeIf { it.pendingContinuityChoice }?.let {
                ContinuityChoice(Format.hoursMinutes(it.positionMs), Format.hoursMinutes(timeline.durationMs))
            },
        )
    }

    fun mini(plan: EditionPlan, edition: LocalEditionEntity?, progress: BookProgressEntity?, live: LivePlayback): MiniPlayerUi {
        val isLive = live.key == plan.key
        val position = plan.timeline.clamp(if (isLive) live.positionMs else progress?.positionMs ?: 0L)
        val failure = failureReason(plan, edition, live)
        val subtitle = if (plan.timeline.division == Division.WHOLE_BOOK) plan.author else segmentTitle(plan, plan.timeline.segmentIndexAt(position))
        val finished = progress?.finished == true
        // The same rule as the full player, so both keys name the same next action.
        val hasPlace = !finished && progress != null && (progress.positionMs > 0 || isLive)
        return MiniPlayerUi(face(plan), subtitle, live.showsPause(plan.key), live.waitsForAudio(plan.key), enabled = failure == null, disabledReason = failure, start = StartAction.of(hasPlace, finished))
    }
}
