package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.server.ResumeRequest
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Response

sealed interface FileTransferResult {
    data class Complete(val validator: String?, val isDate: Boolean, val urlFingerprint: String) : FileTransferResult
    data class Partial(val validator: String?, val isDate: Boolean, val urlFingerprint: String, val bytesOnDisk: Long) : FileTransferResult
    data object Restarted : FileTransferResult
    data object NoAudio : FileTransferResult
    data object Forbidden : FileTransferResult
    data object NotFound : FileTransferResult
    data object SessionExpired : FileTransferResult
    data class Transient(val detail: String) : FileTransferResult

    /**
     * The connection broke while an accepted body was being written. The bytes on disk stay, with
     * the validator and URL fingerprint they came under, so the next attempt can continue them.
     */
    data class Interrupted(val validator: String?, val isDate: Boolean, val urlFingerprint: String, val bytesOnDisk: Long, val detail: String) : FileTransferResult
    data class Mismatch(val detail: String) : FileTransferResult
    data class Unexpected(val detail: String) : FileTransferResult
}

/**
 * Transfers one track file into [file], continuing a partial only when the rules of safe
 * continuation allow it (docs/04-server-and-downloads.md). The decision reads the bytes on disk,
 * never a journal. The body is read on the IO dispatcher, and a cancelled caller stops the copy
 * between chunks with the written prefix kept.
 */
class FileTransfer(
    private val file: File,
    private val expectedSize: Long,
    private val savedValidator: String?,
    private val savedValidatorIsDate: Boolean,
    private val savedUrlFingerprint: String?,
    private val downloadUrl: String,
    private val request: suspend (ResumeRequest?) -> ServerResult<Response>,
    private val progress: (Long) -> Unit,
) {
    suspend fun transfer(): FileTransferResult = withContext(Dispatchers.IO) { transferOnIo(coroutineContext.job) }

    private suspend fun transferOnIo(job: Job): FileTransferResult {
        require(expectedSize >= 0)
        val fingerprint = urlFingerprint(downloadUrl)
        val length = if (file.exists()) file.length() else 0L
        if (length == expectedSize && length > 0) {
            return FileTransferResult.Complete(savedValidator, savedValidatorIsDate, savedUrlFingerprint ?: fingerprint)
        }

        val canResume = length in 1 until expectedSize && savedUrlFingerprint == fingerprint &&
            savedValidator != null && !savedValidator.startsWith("W/", ignoreCase = true)
        if (!canResume && length > 0) {
            try {
                truncate(0)
            } catch (error: IOException) {
                return FileTransferResult.Transient(error.message ?: "Could not restart file")
            }
        }
        val prefix = if (canResume) length else 0L
        val resume = if (canResume) ResumeRequest(prefix, savedValidator) else null
        val response = try {
            request(resume)
        } catch (error: IOException) {
            return FileTransferResult.Transient(error.message ?: "Download request failed")
        }
        return when (val result = response) {
            is ServerResult.Failed -> failed(result.error)
            is ServerResult.Ok -> result.value.use { response -> handle(response, prefix, fingerprint, job) }
        }
    }

    private fun handle(response: Response, prefix: Long, fingerprint: String, job: Job): FileTransferResult {
        if (response.header("X-Accel-Redirect") != null || response.code == 204) return FileTransferResult.NoAudio
        if (response.code == 416) {
            if (file.length() == expectedSize && expectedSize > 0) {
                return FileTransferResult.Complete(savedValidator, savedValidatorIsDate, savedUrlFingerprint ?: fingerprint)
            }
            return try {
                truncate(0)
                FileTransferResult.Restarted
            } catch (error: IOException) {
                FileTransferResult.Transient(error.message ?: "Could not restart file")
            }
        }
        if (response.code in 500..599) return FileTransferResult.Transient("HTTP ${response.code}")
        if (response.code in 201..299 && response.code != 206) {
            return try {
                if (response.body.byteStream().read() < 0) FileTransferResult.NoAudio
                else FileTransferResult.Unexpected("HTTP ${response.code}")
            } catch (error: IOException) {
                FileTransferResult.Transient(error.message ?: "Download interrupted")
            }
        }
        if (response.code != 200 && response.code != 206) return FileTransferResult.Unexpected("HTTP ${response.code}")
        val contentType = response.header("Content-Type").orEmpty()
        if (contentType.startsWith("text/html", ignoreCase = true)) return FileTransferResult.Unexpected("HTML download response")
        if (contentType.startsWith("multipart/", ignoreCase = true)) return FileTransferResult.Mismatch("Multipart range response")
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            return FileTransferResult.Mismatch("Encoded download response")
        }

        val range = if (response.code == 206) parseRange(response.header("Content-Range")) else null
        if (response.code == 206 && !validRange(range, response, prefix)) {
            return FileTransferResult.Mismatch("Range or validator does not match staged file")
        }
        val startLength = if (response.code == 206) prefix else 0L
        val validator = if (response.code == 206) ResumeValidator(savedValidator!!, savedValidatorIsDate)
            else resumeValidator(response.headers)
        return writeBody(response, startLength, range, validator, fingerprint, job)
    }

    private fun validRange(range: ByteRange?, response: Response, prefix: Long): Boolean {
        if (range == null || range.start != prefix || range.start > range.end ||
            range.end >= range.total || range.total != expectedSize) return false
        if (savedValidatorIsDate) return response.header("Last-Modified") == savedValidator
        return response.header("ETag") == savedValidator
    }

    private fun writeBody(
        response: Response,
        startLength: Long,
        range: ByteRange?,
        validator: ResumeValidator?,
        fingerprint: String,
        job: Job,
    ): FileTransferResult {
        var written = 0L
        var lastProgress = startLength
        var lastSync = startLength
        val expectedResponseBytes = range?.let { it.end - it.start + 1 }
        try {
            response.body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var firstCount = 0
                while (firstCount < HTML_SNIFF_BYTES) {
                    val count = input.read(buffer, firstCount, HTML_SNIFF_BYTES - firstCount)
                    if (count < 0) break
                    firstCount += count
                }
                if (firstCount == 0) return FileTransferResult.NoAudio
                if (looksLikeHtml(buffer, firstCount)) return FileTransferResult.Unexpected("HTML download response")
                if (range == null) truncate(0)
                file.parentFile?.mkdirs()
                FileOutputStream(file, true).use { output ->
                    var pending = firstCount
                    try {
                        while (true) {
                            // Pause and Cancel stop the copy here; the written prefix stays for a resume.
                            if (!job.isActive) throw CancellationException("Transfer stopped")
                            val count = if (pending >= 0) pending.also { pending = -1 } else input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (expectedResponseBytes != null && written + count > expectedResponseBytes) {
                                truncate(startLength)
                                return FileTransferResult.Mismatch("Range body exceeds Content-Range")
                            }
                            output.write(buffer, 0, count)
                            written += count
                            val onDisk = startLength + written
                            if (onDisk - lastSync >= SYNC_BYTES) {
                                output.fd.sync()
                                lastSync = onDisk
                            }
                            if (onDisk - lastProgress >= PROGRESS_BYTES) {
                                progress(onDisk)
                                lastProgress = onDisk
                            }
                        }
                    } finally {
                        output.fd.sync()
                    }
                }
            }
        } catch (error: IOException) {
            return FileTransferResult.Interrupted(validator?.value, validator?.isDate ?: false, fingerprint, file.length(), error.message ?: "Download interrupted")
        }
        if (written == 0L) return FileTransferResult.NoAudio
        if (range != null && written != expectedResponseBytes) {
            return try {
                truncate(startLength)
                FileTransferResult.Mismatch("Range body length does not match Content-Range")
            } catch (error: IOException) {
                FileTransferResult.Transient(error.message ?: "Could not restore prefix")
            }
        }
        val onDisk = startLength + written
        if (onDisk > expectedSize) {
            return try {
                truncate(startLength)
                FileTransferResult.Mismatch("File exceeds expected size")
            } catch (error: IOException) {
                FileTransferResult.Transient(error.message ?: "Could not discard oversized file")
            }
        }
        return if (onDisk == expectedSize) {
            FileTransferResult.Complete(validator?.value, validator?.isDate ?: false, fingerprint)
        } else {
            FileTransferResult.Partial(validator?.value, validator?.isDate ?: false, fingerprint, onDisk)
        }
    }

    private fun failed(error: ServerError): FileTransferResult = when (error) {
        ServerError.Forbidden -> FileTransferResult.Forbidden
        ServerError.NotFound -> FileTransferResult.NotFound
        ServerError.SessionExpired -> FileTransferResult.SessionExpired
        is ServerError.Unexpected -> FileTransferResult.Unexpected(error.detail)
        else -> FileTransferResult.Transient(error.toString())
    }

    private fun truncate(length: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(length); it.fd.sync() }
    }

    private fun looksLikeHtml(bytes: ByteArray, count: Int): Boolean {
        val prefix = String(bytes, 0, minOf(count, 64), Charsets.US_ASCII).trimStart()
        return prefix.startsWith("<!DOCTYPE html", ignoreCase = true) || prefix.startsWith("<html", ignoreCase = true)
    }

    private data class ByteRange(val start: Long, val end: Long, val total: Long)

    private fun parseRange(header: String?): ByteRange? {
        val match = header?.let { RANGE_PATTERN.matchEntire(it.trim()) } ?: return null
        val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        return ByteRange(values[0], values[1], values[2])
    }

    companion object {
        private const val HTML_SNIFF_BYTES = 64
        private const val PROGRESS_BYTES = 256L * 1024
        private const val SYNC_BYTES = 8L * 1024 * 1024
        private val RANGE_PATTERN = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun urlFingerprint(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
