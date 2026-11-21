package ch.lkmc.pageworn.transfer

import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.PairingState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.server.ResumeRequest
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/** HTTP shaped transport with no sockets. Each route has independent file bytes and responses. */
internal class FakeTransferServer(private val addresses: suspend () -> List<AddressEntity>) : TransferRoutes {
    data class RequestRecord(val address: String, val fileId: String, val range: String?, val ifRange: String?, val bytes: Int)

    class Gate(val after: Int, val failAfterRelease: Boolean = false) {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    data class Reply(
        val code: Int? = null,
        val body: ByteArray? = null,
        val contentRange: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        val date: String? = null,
        val contentEncoding: String? = null,
        val contentType: String = "audio/mp4",
        val ignoreRange: Boolean = false,
        val cutAfter: Int? = null,
        val gate: Gate? = null,
        val accel: Boolean = false,
    )

    val manifests = mutableMapOf<String, ItemManifest>()
    val manifestErrors = mutableMapOf<String, ServerError>()
    val sources = mutableMapOf<Pair<String, String>, ByteArray>()
    val replies = mutableMapOf<Pair<String, String>, ArrayDeque<Reply>>()
    val requests = mutableListOf<RequestRecord>()
    val manifestCalls = mutableMapOf<String, Int>()
    var selected: String? = null
    var defaultEtag = "\"same-revision\""
    var defaultLastModified: String? = null
    var defaultDate: String? = null

    fun script(address: String, fileId: String, vararg repliesToAdd: Reply) {
        replies.getOrPut(address to fileId) { ArrayDeque() }.addAll(repliesToAdd.asList())
    }

    override suspend fun manifest(itemId: String): Routed<ItemManifest> {
        manifestCalls[itemId] = (manifestCalls[itemId] ?: 0) + 1
        val address = usableAddress() ?: return Routed.Unavailable(NoRoute.Unreachable(null))
        manifestErrors[itemId]?.let { return Routed.Failed(it, address.addressId) }
        return Routed.Done(checkNotNull(manifests[itemId]), address.addressId)
    }

    override suspend fun channel(addressId: String): DownloadChannel? =
        if (addresses().any { it.addressId == addressId && usable(it) }) Channel(addressId) else null

    private suspend fun usableAddress(): AddressEntity? {
        val candidates = addresses().filter(::usable).sortedBy { it.priority }
        return candidates.firstOrNull { it.addressId == selected } ?: candidates.firstOrNull()
    }

    private fun usable(address: AddressEntity) = address.enabled &&
        address.pairingState == PairingState.PAIRED && address.sessionState == SessionState.SIGNED_IN

    private inner class Channel(private val address: String) : DownloadChannel {
        override fun url(itemId: String, fileId: String) = "https://$address.example/$itemId/$fileId"

        override suspend fun cover(itemId: String): ByteArray? = null

        override suspend fun open(itemId: String, fileId: String, resume: ResumeRequest?): ServerResult<Response> {
            val source = checkNotNull(sources[address to fileId]) { "Missing $address/$fileId" }
            val reply = replies[address to fileId]?.pollFirst() ?: Reply()
            val etag = reply.etag ?: defaultEtag
            val modified = reply.lastModified ?: defaultLastModified
            val date = reply.date ?: defaultDate
            val validRange = resume != null && !reply.ignoreRange &&
                (resume.validator == etag && !etag.startsWith("W/") ||
                    resume.validator == modified && dateIsOldEnough(date, modified))
            val code = reply.code ?: if (validRange) 206 else 200
            val start = if (code == 206 && validRange) resume.from.toInt() else 0
            val body = reply.body ?: if (code in 200..299 && code != 204) source.copyOfRange(start.coerceAtMost(source.size), source.size) else byteArrayOf()
            val range = reply.contentRange ?: if (code == 206) "bytes $start-${source.size - 1}/${source.size}" else null
            val rangeHeader = resume?.let { "bytes=${it.from}-" }
            requests += RequestRecord(address, fileId, rangeHeader, resume?.validator, body.size)
            val request = Request.Builder().url(url(itemId, fileId)).header("Accept-Encoding", "identity").apply {
                if (rangeHeader != null) header("Range", rangeHeader)
                if (resume != null) header("If-Range", resume.validator)
            }.build()
            val responseBody = if (reply.cutAfter != null || reply.gate != null) GatedBody(body, reply.cutAfter, reply.gate)
                else body.toResponseBody(reply.contentType.toMediaType())
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("test response").body(responseBody).apply {
                    header("Content-Type", reply.contentType)
                    header("ETag", etag)
                    if (modified != null) header("Last-Modified", modified)
                    if (date != null) header("Date", date)
                    if (range != null) header("Content-Range", range)
                    if (reply.contentEncoding != null) header("Content-Encoding", reply.contentEncoding)
                    if (reply.accel) header("X-Accel-Redirect", "/internal/audio")
                }.build()
            return ServerResult.Ok(response)
        }
    }

    private fun dateIsOldEnough(date: String?, modified: String): Boolean {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val first = date?.let { runCatching { format.parse(it) }.getOrNull() } ?: return false
        val second = runCatching { format.parse(modified) }.getOrNull() ?: return false
        return first.time - second.time >= 60_000
    }

    private class GatedBody(private val bytes: ByteArray, private val cutAfter: Int?, private val gate: Gate?) : ResponseBody() {
        override fun contentType() = "audio/mp4".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            private var offset = 0
            private var waited = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (cutAfter != null && offset >= cutAfter) throw IOException("connection dropped")
                if (gate != null && offset >= gate.after && !waited) {
                    waited = true
                    gate.reached.complete(Unit)
                    runBlocking { gate.release.await() }
                    if (gate.failAfterRelease) throw IOException("address removed")
                }
                if (offset >= bytes.size) return -1
                val boundary = minOf(bytes.size, cutAfter ?: bytes.size, gate?.after?.takeIf { !waited } ?: bytes.size)
                val count = minOf(byteCount.toInt(), boundary - offset)
                if (count <= 0) return read(sink, byteCount)
                sink.write(bytes, offset, count)
                offset += count
                return count.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }
}
