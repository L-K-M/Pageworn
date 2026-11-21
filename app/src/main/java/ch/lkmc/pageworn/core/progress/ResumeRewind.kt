package ch.lkmc.pageworn.core.progress

/**
 * How a book last stopped, stored with its progress so the resume rewind survives process death.
 * See "Resume rewind" in docs/05-playback-and-formats.md.
 */
enum class StopKind {
    /** A pause, an interruption, headphone removal, a book switch, a playback failure or process death. */
    ORDINARY,

    /** A timed sleep stopped audible playback. */
    TIMED,

    /** End-of-chapter, end-of-track or end-of-book sleep, or the listener moved the position while paused. */
    EXACT,
}

object ResumeRewind {
    const val REST_THRESHOLD_MS = 5 * 60_000L
    const val ORDINARY_REWIND_MS = 10_000L
    const val TIMED_REWIND_MS = 5 * 60_000L

    /**
     * Start position for playback that begins from a saved place.
     *
     * @param savedPositionMs the saved book position.
     * @param stopKind how the book last stopped.
     * @param savedAtWallMs wall time at which the saved position was last written.
     * @param nowWallMs current wall time from the injected clock; a clock that moved backwards counts as no rest.
     * @param finished a finished book starts from the beginning.
     */
    fun startPosition(
        savedPositionMs: Long,
        stopKind: StopKind,
        savedAtWallMs: Long,
        nowWallMs: Long,
        finished: Boolean = false,
    ): Long {
        if (finished) return 0L
        val rest = (nowWallMs - savedAtWallMs).coerceAtLeast(0L)
        if (rest < REST_THRESHOLD_MS) return savedPositionMs.coerceAtLeast(0L)
        val rewind = when (stopKind) {
            StopKind.ORDINARY -> ORDINARY_REWIND_MS
            StopKind.TIMED -> TIMED_REWIND_MS
            StopKind.EXACT -> 0L
        }
        return (savedPositionMs - rewind).coerceAtLeast(0L)
    }
}
