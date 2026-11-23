package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.data.SleepSettings
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.core.timeline.Segment
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.playback.PlayProblem
import ch.lkmc.pageworn.playback.PlayRefusal
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
import ch.lkmc.pageworn.ui.model.SleepOption
import ch.lkmc.pageworn.ui.model.SleepUi

/** Builds the player and mini-player state of one book from its edition, progress and the live player. */
object PlayerStates {

    /** What one segment of [division] is called. */
    fun segmentName(division: Division) = when (division) {
        Division.CHAPTERS -> "Chapter"
        Division.TRACKS -> "Track"
        Division.PARTS -> "Part"
    }

    fun segmentsLabel(division: Division) = "${segmentName(division)}s"

    /** What a refused Play says where it was tapped. A choice is shown in the player instead. */
    fun notice(problem: PlayProblem): String? = when (problem.refusal) {
        PlayRefusal.NEEDS_CHOICE -> null
        PlayRefusal.DAMAGED_COPY -> Copy.DAMAGED_COPY
        PlayRefusal.DID_NOT_START -> Copy.PLAY_DID_NOT_START
    }

    fun endLabel(division: Division) = "End of ${segmentName(division).lowercase()}"

    fun segmentTitle(division: Division, segment: Segment): String =
        segment.title?.takeIf { it.isNotBlank() } ?: "${segmentName(division)} ${segment.index + 1}"

    fun segments(plan: EditionPlan, positionMs: Long?): List<SegmentRow> = segments(plan.timeline, positionMs)

    fun segments(timeline: BookTimeline, positionMs: Long?): List<SegmentRow> {
        val current = positionMs?.let { timeline.segmentIndexAt(it) }
        return timeline.segments.mapIndexed { i, s ->
            SegmentRow(i, segmentTitle(timeline.division, s), Format.clock(s.durationMs), s.generated, current == i, s.startMs)
        }
    }

    fun face(plan: EditionPlan) =
        BookFace(plan.key, plan.title, plan.author, plan.coverFile?.let { CoverImage.Local(it) } ?: CoverImage.Cloth)

    /**
     * The Sleep utility and sheet of [book]. A timer that runs for this book shows its countdown;
     * otherwise the utility shows the mode's length while sleep mode is on.
     */
    fun sleep(live: LivePlayback, division: Division, mode: SleepSettings, book: BookKey): SleepUi {
        val endLabel = endLabel(division)
        val options = buildList {
            add(SleepOption(null, "Off"))
            mode.presets.forEach { add(SleepOption(SleepLength.Minutes(it), Format.minutes(it))) }
            add(SleepOption(SleepLength.EndOfSegment, endLabel))
        }
        val selected = mode.length.takeIf { mode.enabled }
        val remaining = live.sleepRemainingMs?.takeIf { live.key == book && live.sleep != SleepState.Off }
        if (remaining != null) {
            return SleepUi(
                options, selected, Format.sleepRemaining(remaining),
                "Sleep, ${Format.spokenDuration(remaining)} remaining", Format.countdown(remaining),
            )
        }
        return when (selected) {
            null -> SleepUi(options, null, null, "Sleep, off")
            SleepLength.EndOfSegment -> SleepUi(options, selected, null, "Sleep, on, ${endLabel.lowercase()}", endLabel)
            is SleepLength.Minutes -> SleepUi(options, selected, null, "Sleep, on, ${Format.minutes(selected.minutes)}", "${selected.minutes} min")
        }
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
        sleepMode: SleepSettings = SleepSettings(),
    ): PlayerUiState {
        val timeline = plan.timeline
        val isLive = live.key == plan.key
        val finished = progress?.finished == true && !live.showsPause(plan.key)
        val position = timeline.clamp(if (isLive) live.positionMs else progress?.positionMs ?: 0L)
        val segment = timeline.segmentAt(position)
        val failure = failureReason(plan, edition, live)
        val failed = failure != null
        val showsPause = live.showsPause(plan.key)
        // Until the listener has chosen where changed audio starts, there is no place to play from.
        val choiceNeeded = progress?.pendingContinuityChoice == true && !showsPause
        return PlayerUiState(
            face = face(plan),
            narrator = edition?.narrators,
            segmentLine = segmentTitle(timeline.division, segment),
            segmentName = segmentName(timeline.division),
            segmentStartMs = segment.startMs,
            segmentEndMs = segment.endMs,
            positionMs = position,
            elapsedText = Format.clock(position - segment.startMs),
            remainingText = Format.remaining(segment.endMs - position),
            bookLine = Format.bookLine(position, timeline.durationMs),
            bookDurationMs = timeline.durationMs,
            showsPause = showsPause,
            starting = live.waitsForAudio(plan.key),
            transportEnabled = !failed && !choiceNeeded,
            transportDisabledReason = failure ?: Copy.CHOOSE_START.takeIf { choiceNeeded },
            failureMessage = failure,
            speed = live.speed,
            sleep = sleep(live, timeline.division, sleepMode, plan.key),
            segmentsLabel = segmentsLabel(timeline.division),
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
        val subtitle = segmentTitle(plan.timeline.division, plan.timeline.segmentAt(position))
        val finished = progress?.finished == true
        // The same rule as the full player, so both keys name the same next action.
        val hasPlace = !finished && progress != null && (progress.positionMs > 0 || isLive)
        return MiniPlayerUi(face(plan), subtitle, live.showsPause(plan.key), live.waitsForAudio(plan.key), enabled = failure == null, disabledReason = failure, start = StartAction.of(hasPlace, finished))
    }
}
