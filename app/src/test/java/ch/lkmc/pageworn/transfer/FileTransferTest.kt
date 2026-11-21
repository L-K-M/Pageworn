package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.server.ResumeRequest
import ch.lkmc.pageworn.server.ServerResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTransferTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val source = "abcdefghij".toByteArray()
    private val strongTag = "\"revision-one\""
    private val url = "https://route.example/item/file/download"
    private val fingerprint get() = FileTransfer.urlFingerprint(url)
    private val oldDate = "Sat, 21 Nov 2026 15:00:00 GMT"
    private val serverDate = "Sat, 21 Nov 2026 15:02:00 GMT"

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    private fun file(prefix: String = "abc"): File = folder.newFile("audio.m4a").apply { writeText(prefix) }

    private fun response(code: Int, body: String = "", vararg headers: Pair<String, String>): MockResponse =
        MockResponse().setResponseCode(code).setBody(Buffer().writeUtf8(body)).apply {
            headers.forEach { (name, value) -> addHeader(name, value) }
        }

    private fun transfer(
        file: File,
        validator: String? = strongTag,
        isDate: Boolean = false,
        savedFingerprint: String? = fingerprint,
        currentUrl: String = url,
        expected: Long = source.size.toLong(),
        beforeRequest: (() -> Unit)? = null,
    ): FileTransferResult = runBlocking {
        FileTransfer(file, expected, validator, isDate, savedFingerprint, currentUrl,
            request = { resume: ResumeRequest? ->
                beforeRequest?.invoke()
                val builder = Request.Builder().url(server.url("/download"))
                    .header("Accept-Encoding", "identity")
                if (resume != null) {
                    builder.header("Range", "bytes=${resume.from}-")
                    builder.header("If-Range", resume.validator)
                }
                ServerResult.Ok(client.newCall(builder.build()).execute())
            }, progress = {}).transfer()
    }

    @Test fun cancellingStopsTheCopyAndKeepsTheWrittenPrefix() = runBlocking {
        // A 4 MiB body sent in 64 KiB pieces every 50 ms: Pause lands while bytes are flowing.
        val size = 4L * 1024 * 1024
        val body = Buffer().write(ByteArray(size.toInt()) { (it % 251).toByte() })
        server.enqueue(MockResponse().setResponseCode(200).setBody(body).throttleBody(64 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS))
        val target = folder.newFile("long.m4a")
        val received = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            FileTransfer(target, size, null, false, null, url,
                request = { ServerResult.Ok(client.newCall(Request.Builder().url(server.url("/download")).build()).execute()) },
                progress = { received.complete(Unit) }).transfer()
        }
        received.await()
        job.cancelAndJoin()
        assertThat(job.isCancelled).isTrue()
        assertThat(target.length()).isGreaterThan(0L)
        assertThat(target.length()).isLessThan(size)
    }

    @Test fun a05ValidShort206AdvancesOnlyToRangeEnd() {
        val file = file()
        server.enqueue(response(206, "def", "Content-Range" to "bytes 3-5/10", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Partial::class.java)
        assertThat(file.readText()).isEqualTo("abcdef")
        assertThat(server.takeRequest().getHeader("If-Range")).isEqualTo(strongTag)
    }

    @Test fun a05MismatchedValidatorKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "defghij", "Content-Range" to "bytes 3-9/10", "ETag" to "\"other\""))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05WrongStartKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "defghij", "Content-Range" to "bytes 2-8/10", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05WrongTotalKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "defghij", "Content-Range" to "bytes 3-9/11", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05EndAtTotalKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "defghij", "Content-Range" to "bytes 3-10/10", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05ShortRangeBodyKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "def", "Content-Range" to "bytes 3-9/10", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05LongRangeBodyKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "defghijk", "Content-Range" to "bytes 3-9/10", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05MultipartRangeKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "multipart body", "Content-Type" to "multipart/byteranges; boundary=x",
            "Content-Range" to "bytes 3-9/10", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05GzipRangeKeepsPrefix() {
        val file = file()
        server.enqueue(response(206, "defghij", "Content-Range" to "bytes 3-9/10",
            "ETag" to strongTag, "Content-Encoding" to "gzip"))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Mismatch::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05Full200ReplacesPrefixAndMatchesSource() {
        val file = file("old")
        server.enqueue(response(200, String(source), "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Complete::class.java)
        assertThat(file.readBytes()).isEqualTo(source)
    }

    @Test fun a05HtmlBodyKeepsPrefix() {
        val file = file()
        server.enqueue(response(200, "<!DOCTYPE html><html>login</html>"))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Unexpected::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun a05Truncated200CannotComplete() {
        val file = file()
        server.enqueue(response(200, "abcde", "ETag" to strongTag))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Partial::class.java)
        assertThat(file.readText()).isEqualTo("abcde")
    }

    @Test fun a05CompleteFileNeedsNoRequest() {
        val file = file(String(source))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Complete::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(file.readBytes()).isEqualTo(source)
    }

    @Test fun a05ConcurrentCompletionBefore416IsComplete() {
        val file = file()
        server.enqueue(response(416))
        val result = transfer(file, beforeRequest = { file.writeBytes(source) })
        assertThat(result).isInstanceOf(FileTransferResult.Complete::class.java)
        assertThat(file.readBytes()).isEqualTo(source)
    }

    @Test fun a05ShortFile416Restarts() {
        val file = file()
        server.enqueue(response(416))
        assertThat(transfer(file)).isEqualTo(FileTransferResult.Restarted)
        assertThat(file.length()).isEqualTo(0)
    }

    @Test fun a05Accel204HasNoAudioAndWritesNothing() {
        val file = file("")
        server.enqueue(response(204, "", "X-Accel-Redirect" to "/internal/audio"))
        assertThat(transfer(file)).isEqualTo(FileTransferResult.NoAudio)
        assertThat(file.length()).isEqualTo(0)
    }

    @Test fun a05Empty200IsNoAudio() {
        val file = file("")
        server.enqueue(response(200))
        assertThat(transfer(file)).isEqualTo(FileTransferResult.NoAudio)
        assertThat(file.length()).isEqualTo(0)
    }

    @Test fun a05bServer500IsTransientAndKeepsPrefix() {
        val file = file()
        server.enqueue(response(500, "error"))
        assertThat(transfer(file)).isInstanceOf(FileTransferResult.Transient::class.java)
        assertThat(file.readText()).isEqualTo("abc")
    }

    @Test fun aDroppedConnectionKeepsItsBytesAndTheirValidator() {
        // The server hangs up halfway through a full 200: the bytes on disk can be continued.
        val size = 1024 * 1024
        val file = file("")
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(size) { 7 }))
            .addHeader("ETag", strongTag).setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = transfer(file, validator = null, savedFingerprint = null, expected = size.toLong())
        assertThat(result).isInstanceOf(FileTransferResult.Interrupted::class.java)
        result as FileTransferResult.Interrupted
        assertThat(result.validator).isEqualTo(strongTag)
        assertThat(result.urlFingerprint).isEqualTo(fingerprint)
        assertThat(result.bytesOnDisk).isEqualTo(file.length())
        assertThat(file.length()).isGreaterThan(0L)
        assertThat(file.length()).isLessThan(size.toLong())
    }

    @Test fun aFullDiskIsAStorageFailureThatKeepsTheWrittenPrefix() {
        // The disk refuses a write partway through a full 200: the failure is the phone's
        // storage, not the connection, and the bytes already written can be continued.
        val size = 1024 * 1024
        val limit = 256L * 1024
        val file = file("")
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(size) { 7 })).addHeader("ETag", strongTag))
        val result = runBlocking {
            FileTransfer(file, size.toLong(), null, false, null, url,
                request = { ServerResult.Ok(client.newCall(Request.Builder().url(server.url("/download")).build()).execute()) },
                progress = {},
                openOutput = { target -> FullDisk(target, limit) }).transfer()
        }
        assertThat(result).isInstanceOf(FileTransferResult.StorageFailed::class.java)
        result as FileTransferResult.StorageFailed
        assertThat(result.validator).isEqualTo(strongTag)
        assertThat(result.urlFingerprint).isEqualTo(fingerprint)
        assertThat(result.bytesOnDisk).isEqualTo(file.length())
        assertThat(file.length()).isGreaterThan(0L)
        assertThat(file.length()).isAtMost(limit)
    }

    /** A file stream that fails as a full disk does once [limit] bytes are on disk. */
    private class FullDisk(file: File, private val limit: Long) : FileOutputStream(file, true) {
        private var onDisk = file.length()

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (onDisk + len > limit) throw IOException("No space left on device")
            super.write(b, off, len)
            onDisk += len
        }
    }

    @Test fun a05dWeakTagUsesOldEnoughLastModified() {
        val file = file("")
        server.enqueue(response(200, "abc", "ETag" to "W/\"weak\"",
            "Last-Modified" to oldDate, "Date" to serverDate))
        val first = transfer(file, validator = null, savedFingerprint = null)
        assertThat(first).isInstanceOf(FileTransferResult.Partial::class.java)
        assertThat((first as FileTransferResult.Partial).validator).isEqualTo(oldDate)
        server.enqueue(response(206, "defghij", "Content-Range" to "bytes 3-9/10",
            "ETag" to "W/\"weak\"", "Last-Modified" to oldDate))
        val second = transfer(file, validator = first.validator, isDate = first.isDate,
            savedFingerprint = first.urlFingerprint)
        assertThat(second).isInstanceOf(FileTransferResult.Complete::class.java)
        assertThat(server.takeRequest().getHeader("If-Range")).isNull()
        assertThat(server.takeRequest().getHeader("If-Range")).isEqualTo(oldDate)
        assertThat(file.readBytes()).isEqualTo(source)
    }

    @Test fun a05dRecentLastModifiedCannotResume() {
        val file = file("")
        server.enqueue(response(200, "abc", "ETag" to "W/\"weak\"",
            "Last-Modified" to "Sat, 21 Nov 2026 15:01:30 GMT", "Date" to serverDate))
        val first = transfer(file, validator = null, savedFingerprint = null) as FileTransferResult.Partial
        assertThat(first.validator).isNull()
        server.enqueue(response(200, String(source), "ETag" to strongTag))
        assertThat(transfer(file, validator = first.validator, savedFingerprint = first.urlFingerprint))
            .isInstanceOf(FileTransferResult.Complete::class.java)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Range")).isNull()
        assertThat(file.readBytes()).isEqualTo(source)
    }

    @Test fun a04ChangedUrlFingerprintRestartsPartialFile() {
        val file = file("old")
        server.enqueue(response(200, String(source), "ETag" to strongTag))
        val result = transfer(file, savedFingerprint = FileTransfer.urlFingerprint("https://other/route"))
        assertThat(result).isInstanceOf(FileTransferResult.Complete::class.java)
        assertThat(server.takeRequest().getHeader("Range")).isNull()
        assertThat(file.readBytes()).isEqualTo(source)
    }
}
