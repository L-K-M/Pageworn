package ch.lkmc.pageworn.core

import android.os.SystemClock

/**
 * Injected time source. Wall time decides rest and sort order; elapsed realtime owns sleep deadlines,
 * because it keeps counting while the phone sleeps and never jumps when the user changes the date.
 */
interface Clock {
    fun wallMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

object SystemClockSource : Clock {
    override fun wallMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/** Deterministic clock for tests. Both readings advance together unless set separately. */
class FakeClock(var wall: Long = 1_800_000_000_000L, var elapsed: Long = 10_000L) : Clock {
    override fun wallMillis(): Long = wall
    override fun elapsedRealtimeMillis(): Long = elapsed
    fun advance(ms: Long) {
        wall += ms
        elapsed += ms
    }
}
