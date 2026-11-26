package ch.lkmc.pageworn.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import ch.lkmc.pageworn.R

/** Short foreground cues mixed by Android as sonification, without requesting audio focus. */
internal class UiSounds(context: Context, private val enabled: () -> Boolean) {
    private val pool = SoundPool.Builder()
        // A finished clip can still occupy its stream while Android drains the output.
        // Leave room for nearby taps and for reusing a track for each cue.
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val loaded = mutableSetOf<Int>()
    private val key: Int
    private val light: Int

    init {
        // The listener runs on the main looper, where the activity also calls play and close.
        pool.setOnLoadCompleteListener { _, sample, status -> if (status == 0) loaded.add(sample) }
        key = pool.load(context, R.raw.pw_key_click, 1)
        light = pool.load(context, R.raw.pw_light_tick, 1)
    }

    fun play(feel: TapFeel) {
        if (!enabled()) return
        val sample = when (feel) {
            TapFeel.KEY -> key
            TapFeel.LIGHT -> light
        }
        if (sample in loaded) pool.play(sample, VOLUME, VOLUME, 1, 0, 1f)
    }

    fun close() {
        pool.setOnLoadCompleteListener(null)
        pool.release()
    }

    private companion object {
        const val VOLUME = 0.65f
    }
}
