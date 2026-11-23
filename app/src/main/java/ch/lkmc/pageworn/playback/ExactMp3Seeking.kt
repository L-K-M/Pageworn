package ch.lkmc.pageworn.playback

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp3.Mp3Extractor
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections

/**
 * Exact seeking for local MP3 files.
 *
 * Media3 1.11 seeks a VBR MP3 that carries a Xing table by interpolating that 100-entry table, and
 * uses its index seeker only when no table exists. In a 21-minute VBR file this lands about 1.4 s
 * away from the requested audio (measured with fmt-mp3-vbr-long). Audiobook files are local, so
 * this wrapper scans the frame headers once, gives the player an exact frame index as its seek map,
 * and corrects the extractor's sample timestamps after each seek so they match that index.
 */
class ExactMp3ExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
        val file = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val extractors = delegate.createExtractors(uri, responseHeaders)
        if (file == null || !file.isFile) return extractors
        return Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is Mp3Extractor) ExactSeekMp3Extractor(e, file) else e
        }
    }
}

/** Frame positions and start times of an MP3 file's audio frames (the Xing/Info frame excluded). */
class Mp3FrameIndex private constructor(
    private val positions: LongArray,
    private val timesUs: LongArray,
    val durationUs: Long,
) {
    val frameCount: Int get() = positions.size

    fun timeAtPosition(position: Long): Long? {
        val i = positions.binarySearch(position)
        return if (i >= 0) timesUs[i] else null
    }

    fun seekMap(): SeekMap = object : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = this@Mp3FrameIndex.durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            var i = timesUs.binarySearch(timeUs.coerceAtLeast(0))
            if (i < 0) i = (-i - 2).coerceAtLeast(0)
            val first = SeekPoint(timesUs[i], positions[i])
            return if (timesUs[i] == timeUs || i + 1 >= positions.size) {
                SeekMap.SeekPoints(first)
            } else {
                SeekMap.SeekPoints(first, SeekPoint(timesUs[i + 1], positions[i + 1]))
            }
        }
    }

    companion object {
        /** Enough for the tracks on both sides of the place in the current book and the one before. */
        private const val CACHED_INDEXES = 4

        private data class Stamp(val path: String, val length: Long, val modifiedAt: Long)

        private val cache: MutableMap<Stamp, Mp3FrameIndex> = Collections.synchronizedMap(
            object : LinkedHashMap<Stamp, Mp3FrameIndex>(CACHED_INDEXES, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stamp, Mp3FrameIndex>) = size > CACHED_INDEXES
            },
        )

        /** [scan], once per unchanged file: a book opened again starts without another scan. */
        fun of(file: File): Mp3FrameIndex? {
            val stamp = Stamp(file.path, file.length(), file.lastModified())
            return cache[stamp] ?: scan(file)?.also { cache[stamp] = it }
        }

        /** Scans [file]; returns null when it does not look like a plain MPEG audio stream. */
        fun scan(file: File): Mp3FrameIndex? = runCatching { scanOrThrow(file) }.getOrNull()

        private fun scanOrThrow(file: File): Mp3FrameIndex? {
            RandomAccessFile(file, "r").use { raw ->
                val raf = WindowedFile(raw)
                val length = raw.length()
                var pos = 0L
                val head = ByteArray(10)
                // Skip ID3v2 tags (there may be several).
                while (pos + 10 <= length) {
                    raf.seek(pos)
                    raf.readFully(head)
                    if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) break
                    val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
                        ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
                    val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
                    pos += 10 + size + footer
                }
                // Find the first frame sync.
                val probe = ByteArray(4)
                var first = -1L
                var scanFrom = pos
                while (scanFrom + 4 <= length && scanFrom < pos + 64 * 1024) {
                    raf.seek(scanFrom)
                    raf.readFully(probe)
                    val h = header(probe)
                    if (isSync(h) && MpegAudioUtil.getFrameSize(h) > 0) {
                        first = scanFrom
                        break
                    }
                    scanFrom++
                }
                if (first < 0) return null
                val positions = LongList()
                val times = LongList()
                val parsed = MpegAudioUtil.Header()
                var samples = 0L
                var sampleRate = 0
                var p = first
                var index = 0
                val frameHead = ByteArray(48)
                while (p + 4 <= length) {
                    raf.seek(p)
                    val n = raf.read(frameHead, 0, minOf(frameHead.size.toLong(), length - p).toInt())
                    if (n < 4) break
                    val h = header(frameHead)
                    if (!isSync(h) || !parsed.setForHeaderData(h)) break
                    if (sampleRate == 0) sampleRate = parsed.sampleRate
                    if (parsed.sampleRate != sampleRate) break
                    val frameSize = parsed.frameSize
                    if (frameSize <= 0 || p + frameSize > length + 1) break
                    val isInfoFrame = index == 0 && hasInfoTag(frameHead, n, parsed)
                    if (!isInfoFrame) {
                        positions.add(p)
                        times.add(samples * 1_000_000L / sampleRate)
                        samples += parsed.samplesPerFrame
                    }
                    p += frameSize
                    index++
                }
                if (positions.size == 0 || sampleRate == 0) return null
                return Mp3FrameIndex(positions.toLongArray(), times.toLongArray(), samples * 1_000_000L / sampleRate)
            }
        }

        private fun header(b: ByteArray): Int =
            ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)

        private fun isSync(h: Int) = (h and 0xFFE00000.toInt()) == 0xFFE00000.toInt()

        /** A Xing, Info or VBRI tag in the first frame marks it as metadata rather than audio. */
        private fun hasInfoTag(frame: ByteArray, n: Int, header: MpegAudioUtil.Header): Boolean {
            val mpeg1 = header.version == 3
            val mono = header.channels == 1
            val xingOffset = 4 + if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
            fun tagAt(o: Int, tag: String) = o + 4 <= n && (0 until 4).all { frame[o + it] == tag[it].code.toByte() }
            return tagAt(xingOffset, "Xing") || tagAt(xingOffset, "Info") || tagAt(36, "VBRI")
        }
    }
}

/**
 * Forward reads through one window. A ten-hour book has about 1.4 million frames: a seek and a read
 * per frame took seconds, during which Play had nothing to play.
 */
private class WindowedFile(private val file: RandomAccessFile) {
    private val window = ByteArray(WINDOW_BYTES)
    private var windowStart = 0L
    private var windowLength = 0
    private val fileLength = file.length()
    private var at = 0L

    fun seek(position: Long) {
        at = position
    }

    fun read(target: ByteArray, offset: Int, count: Int): Int {
        val wanted = minOf(count.toLong(), fileLength - at).toInt()
        if (wanted <= 0) return -1
        if (at < windowStart || at + wanted > windowStart + windowLength) {
            windowStart = at
            windowLength = minOf(window.size.toLong(), fileLength - at).toInt()
            file.seek(at)
            file.readFully(window, 0, windowLength)
        }
        window.copyInto(target, offset, (at - windowStart).toInt(), (at - windowStart).toInt() + wanted)
        at += wanted
        return wanted
    }

    fun readFully(target: ByteArray) {
        if (read(target, 0, target.size) != target.size) throw EOFException()
    }

    private companion object {
        const val WINDOW_BYTES = 256 * 1024
    }
}

/** Longs without a box each. */
private class LongList {
    private var values = LongArray(1 shl 14)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun toLongArray(): LongArray = values.copyOf(size)
}

/** Wraps [delegate] with the exact frame index of [file]. */
class ExactSeekMp3Extractor(private val delegate: Extractor, private val file: File) : Extractor {
    private val index: Mp3FrameIndex? by lazy { Mp3FrameIndex.of(file) }

    /** Time of the frame the extractor will read next after a seek, by the exact index. */
    private var pendingTrueTimeUs: Long? = 0L
    private var offsetUs = 0L

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = CorrectingTrackOutput(output.track(id, type))
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(index?.seekMap() ?: seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        delegate.seek(position, timeUs)
        pendingTrueTimeUs = if (position == 0L) 0L else index?.timeAtPosition(position)
        offsetUs = 0L
    }

    override fun release() = delegate.release()

    private inner class CorrectingTrackOutput(private val out: TrackOutput) : TrackOutput {
        override fun format(format: Format) = out.format(format)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            out.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            out.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            pendingTrueTimeUs?.let { trueTime ->
                // The first sample after a seek starts at a frame of the exact index.
                offsetUs = trueTime - timeUs
                pendingTrueTimeUs = null
            }
            out.sampleMetadata(timeUs + offsetUs, flags, size, offset, cryptoData)
        }
    }
}
