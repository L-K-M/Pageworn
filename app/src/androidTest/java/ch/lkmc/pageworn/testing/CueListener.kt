package ch.lkmc.pageworn.testing

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Listens to the decoded PCM that the player is about to output, so a test can measure what was
 * heard rather than the timestamp the decoder reports. The fixtures carry 400 ms tone cues at known
 * frequencies on a quiet 180 Hz bed; [onsetMs] finds where a cue starts in the captured audio.
 *
 * The buffer restarts at every flush, which the audio sink performs on each seek, so sample 0 is the
 * first sample rendered from the seek target.
 */
class CueListener : TeeAudioProcessor.AudioBufferSink {
    @Volatile var sampleRate = 0
        private set
    private var channels = 1
    private var encoding = C.ENCODING_PCM_16BIT
    private var samples = FloatArray(48_000 * 30)
    private var count = 0
    private val lock = Any()

    /** Frames captured since the last flush, divided by the sample rate. */
    val capturedMs: Long get() = synchronized(lock) { if (sampleRate == 0) 0 else count * 1000L / sampleRate }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            sampleRate = sampleRateHz
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            count = 0
        }
    }

    fun reset() = synchronized(lock) { count = 0 }

    override fun handleBuffer(buffer: ByteBuffer) {
        synchronized(lock) {
            val b = buffer.duplicate().order(ByteOrder.nativeOrder())
            when (encoding) {
                C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * channels) {
                    var sum = 0f
                    repeat(channels) { sum += b.float }
                    append(sum / channels)
                }
                C.ENCODING_PCM_32BIT -> while (b.remaining() >= 4 * channels) {
                    var sum = 0f
                    repeat(channels) { sum += b.int / 2147483648f }
                    append(sum / channels)
                }
                C.ENCODING_PCM_24BIT -> while (b.remaining() >= 3 * channels) {
                    var sum = 0f
                    repeat(channels) {
                        val v = (b.get().toInt() and 0xff) or ((b.get().toInt() and 0xff) shl 8) or (b.get().toInt() shl 16)
                        sum += v / 8388608f
                    }
                    append(sum / channels)
                }
                else -> while (b.remaining() >= 2 * channels) {
                    var sum = 0f
                    repeat(channels) { sum += b.short / 32768f }
                    append(sum / channels)
                }
            }
        }
    }

    private fun append(v: Float) {
        if (count == samples.size) samples = samples.copyOf(samples.size * 2)
        samples[count++] = v
    }

    /**
     * Milliseconds from the start of the capture to the onset of a tone at [freqHz], or null if none
     * was heard. Uses 5 ms Goertzel windows and an amplitude threshold well above the bed.
     */
    fun onsetMs(freqHz: Int, threshold: Float = 0.15f): Long? = synchronized(lock) {
        val rate = sampleRate
        if (rate == 0) return null
        val window = rate / 200
        var start = 0
        while (start + window <= count) {
            if (amplitude(start, window, freqHz, rate) >= threshold) return start * 1000L / rate
            start += window
        }
        null
    }

    private fun amplitude(start: Int, n: Int, freq: Int, rate: Int): Float {
        val k = 2.0 * cos(2.0 * PI * freq / rate)
        var s1 = 0.0
        var s2 = 0.0
        for (i in start until start + n) {
            val s0 = samples[i] + k * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - k * s1 * s2
        return (2.0 * sqrt(power.coerceAtLeast(0.0)) / n).toFloat()
    }
}

/** A renderers factory whose audio sink tees decoded PCM into [listener]. */
class ListeningRenderersFactory(context: Context, private val listener: CueListener) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(TeeAudioProcessor(listener)))
            .build()
}
