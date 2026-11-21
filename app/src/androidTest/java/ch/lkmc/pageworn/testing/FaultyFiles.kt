package ch.lkmc.pageworn.testing

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The fault-injecting file store of A10d and A10e: the playback service reads every track through
 * it once it is the container's `playbackDataSourceFactory`. A rule names a track file and fails
 * its opening, or its reads from a byte offset on, a number of times.
 */
class FaultyFiles : DataSource.Factory {

    sealed interface Fault { val fileName: String }

    /** Opening the file fails, as a track that cannot be opened does. */
    data class FailOpen(override val fileName: String) : Fault

    /**
     * A read that reaches [fromOffset] fails, [times] times in all; after that reads succeed, and
     * the first successful one there waits [retryDelayMs] first, a retry still in flight. While
     * [heldUntil] is closed, such a read waits before failing: the loader cannot pass the fault, and
     * the failure happens only once the test opens the latch.
     */
    data class FailReads(
        override val fileName: String,
        val fromOffset: Long,
        val times: Int = Int.MAX_VALUE,
        val retryDelayMs: Long = 0,
        val heldUntil: CountDownLatch? = null,
    ) : Fault {
        internal val left = AtomicInteger(times)
        internal val delayed = AtomicInteger(if (retryDelayMs > 0) 1 else 0)
    }

    @Volatile var faults: List<Fault> = emptyList()

    /** Injected failures, by file name. */
    val failures = CopyOnWriteArrayList<String>()

    /** Where each injected read failure happened: file name and byte position. */
    val failedAt = CopyOnWriteArrayList<Pair<String, Long>>()

    /** Bytes delivered to the player, by file name: whether any audio of a track was read. */
    val delivered = CopyOnWriteArrayList<Pair<String, Int>>()

    fun deliveredFrom(fileName: String): Long = delivered.filter { it.first == fileName }.sumOf { it.second.toLong() }

    override fun createDataSource(): DataSource = Source()

    private inner class Source : DataSource {
        private val inner = FileDataSource()
        private var name: String? = null
        private var position = 0L

        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val fileName = dataSpec.uri.lastPathSegment.orEmpty()
            name = fileName
            position = dataSpec.position
            if (faults.any { it is FailOpen && it.fileName == fileName }) {
                failures += fileName
                throw FileDataSource.FileDataSourceException(FileNotFoundException("injected: $fileName cannot be opened"), PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            }
            return inner.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val fileName = name
            val rule = faults.firstOrNull { it is FailReads && it.fileName == fileName && position + length > it.fromOffset } as FailReads?
            if (rule != null) {
                rule.heldUntil?.let { latch -> cancellable { latch.await() } }
                if (rule.left.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    failures += fileName.orEmpty()
                    failedAt += fileName.orEmpty() to position
                    throw IOException("injected read failure in $fileName at $position")
                }
                if (rule.delayed.getAndSet(0) > 0) cancellable { Thread.sleep(rule.retryDelayMs) }
            }
            val read = inner.read(buffer, offset, length)
            if (read > 0) {
                position += read
                delivered += fileName.orEmpty() to read
            }
            return read
        }

        /** The loader cancels a load by interrupting it; a wait it cuts short is not a read failure. */
        private inline fun cancellable(wait: () -> Unit) {
            try {
                wait()
            } catch (cancelled: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("load cancelled")
            }
        }

        override fun getUri(): Uri? = inner.uri

        override fun close() = inner.close()
    }

    companion object {
        /**
         * The byte position of the first sample at or after [atMs] in an MP4 track, from the seek map
         * that Media3's own MP4 extractor builds from the file's sample table. Every AAC frame is a
         * sync sample, so this is where the player starts reading for that time.
         */
        fun mp4OffsetAt(file: File, atMs: Long): Long = mp4SeekMap(file).getSeekPoints(atMs * 1_000).second.position

        /** The time of the first sample at or after byte [position] of an MP4 track: the inverse of [mp4OffsetAt]. */
        fun mp4TimeAt(file: File, position: Long): Long {
            val map = mp4SeekMap(file)
            var low = 0L
            var high = map.durationUs / 1_000
            while (low < high) {
                val mid = (low + high) / 2
                if (map.getSeekPoints(mid * 1_000).second.position < position) low = mid + 1 else high = mid
            }
            return low
        }

        private fun mp4SeekMap(file: File): SeekMap {
            val output = SeekMapOutput()
            val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)
            extractor.init(output)
            val holder = PositionHolder()
            var at = 0L
            while (output.seekMap == null) {
                val source = FileDataSource()
                source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(at).build())
                try {
                    val input = DefaultExtractorInput(source, at, C.LENGTH_UNSET.toLong())
                    var result = Extractor.RESULT_CONTINUE
                    while (result == Extractor.RESULT_CONTINUE && output.seekMap == null) result = extractor.read(input, holder)
                    if (result == Extractor.RESULT_END_OF_INPUT) throw AssertionError("${file.name} has no seek map")
                    if (result == Extractor.RESULT_SEEK) at = holder.position
                } finally {
                    source.close()
                }
            }
            extractor.release()
            return output.seekMap!!
        }
    }

    private class SeekMapOutput : ExtractorOutput {
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }
}
