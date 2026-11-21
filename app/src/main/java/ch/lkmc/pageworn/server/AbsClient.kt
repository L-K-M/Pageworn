package ch.lkmc.pageworn.server

import ch.lkmc.pageworn.catalog.CatalogEntry
import ch.lkmc.pageworn.catalog.CatalogPage
import ch.lkmc.pageworn.catalog.RemoteLibrary
import ch.lkmc.pageworn.core.timeline.RawChapter
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

data class ServerStatus(val serverVersion: String, val isInit: Boolean, val authMethods: List<String>)

data class AccountDetails(
    val accountId: String,
    val username: String,
    val canDownload: Boolean,
    val librariesAccessible: List<String>,
)

data class Account(
    val accountId: String,
    val username: String,
    val canDownload: Boolean,
    val librariesAccessible: List<String>,
    val tokens: TokenPair,
)

fun signInBlocker(status: ServerStatus): ServerError? {
    val parts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(status.serverVersion)
        ?.groupValues?.drop(1)?.map(String::toInt)
    if (parts == null || parts < listOf(2, 26, 0)) return ServerError.ServerTooOld(status.serverVersion)
    if ("local" !in status.authMethods) return ServerError.NoPasswordSignIn
    return null
}

private operator fun List<Int>.compareTo(other: List<Int>): Int {
    for (index in indices) {
        val comparison = this[index].compareTo(other[index])
        if (comparison != 0) return comparison
    }
    return 0
}

class AbsClient(private val http: AddressHttp, private val json: Json = Json { ignoreUnknownKeys = true }) {
    suspend fun status(): ServerResult<ServerStatus> = get("status", operation = DiagnosticOperation.STATUS) { body ->
        val obj = body.jsonObject
        ServerStatus(obj.string("serverVersion"), obj.required("isInit").jsonPrimitive.boolean,
            obj.required("authMethods").jsonArray.map { it.jsonPrimitive.content })
    }

    suspend fun login(username: String, password: String): ServerResult<Account> {
        val body = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("username", kotlinx.serialization.json.JsonPrimitive(username))
                put("password", kotlinx.serialization.json.JsonPrimitive(password))
            })
        val request = request("login").header("x-return-tokens", "true")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        return send(request, authorized = false, login = true, operation = DiagnosticOperation.SIGN_IN) { account(it) }
    }

    suspend fun refresh(refreshToken: String): ServerResult<Account> = send(
        request("auth/refresh").header("x-refresh-token", refreshToken).post(emptyBody()).build(),
        authorized = false, refresh = true, operation = DiagnosticOperation.TOKEN_REFRESH,
    ) { account(it) }

    suspend fun logout(refreshToken: String): ServerResult<Unit> = send(
        request("logout").header("x-refresh-token", refreshToken).post(emptyBody()).build(),
        operation = DiagnosticOperation.SIGN_OUT,
    ) { }

    suspend fun me(access: String): ServerResult<AccountDetails> = get("api/me", access, operation = DiagnosticOperation.ACCOUNT) { body ->
        details(body.jsonObject)
    }

    suspend fun libraries(access: String): ServerResult<List<RemoteLibrary>> =
        get("api/libraries", access, operation = DiagnosticOperation.LIBRARIES) { body ->
            body.jsonObject.required("libraries").jsonArray.map { it.jsonObject }
                .filter { it.string("mediaType") == "book" }
                .map { RemoteLibrary(it.string("id"), it.string("name"), it.required("displayOrder").jsonPrimitive.int) }
                .sortedBy(RemoteLibrary::displayOrder)
        }

    suspend fun itemsPage(access: String, libraryId: String, page: Int, limit: Int): ServerResult<CatalogPage> {
        require(page >= 0)
        require(limit > 0)
        return items(access, libraryId, page, limit)
    }

    suspend fun allItems(access: String, libraryId: String): ServerResult<CatalogPage> =
        items(access, libraryId, null, 0)

    /** The expanded item: its playable tracks joined to their audio files, chapters and metadata. */
    suspend fun item(access: String, itemId: String): ServerResult<ItemManifest> {
        val url = http.policy.baseUrl.resolve("api/items/").newBuilder()
            .addPathSegment(itemId).addQueryParameter("expanded", "1").build()
        return send(Request.Builder().url(url).header("Authorization", "Bearer $access").get().build(),
            authorized = true, operation = DiagnosticOperation.ITEM_DETAILS) { parseItem(it.jsonObject) }
    }

    /**
     * One original audio file from the permission-checked download route, never the streaming
     * route. The caller owns and closes a successful response, whatever its status, and reads its
     * body off the main thread.
     */
    suspend fun download(access: String, itemId: String, fileId: String, resume: ResumeRequest?): ServerResult<Response> {
        val url = http.policy.baseUrl.resolve("api/items/").newBuilder()
            .addPathSegment(itemId).addPathSegment("file").addPathSegment(fileId)
            .addPathSegment("download").build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $access")
            .header("Accept-Encoding", "identity").get()
        if (resume != null) {
            builder.header("Range", "bytes=${resume.from}-")
            builder.header("If-Range", resume.validator)
        }
        return when (val result = http.executeFollowingRedirects(builder.build(), DiagnosticOperation.DOWNLOAD)) {
            is ServerResult.Failed -> result
            is ServerResult.Ok -> when (result.value.code) {
                401 -> result.value.close().let { ServerResult.Failed(ServerError.SessionExpired) }
                403 -> result.value.close().let { ServerResult.Failed(ServerError.Forbidden) }
                404 -> result.value.close().let { ServerResult.Failed(ServerError.NotFound) }
                else -> result
            }
        }
    }

    /** The effective download URL of one file through this address, for the partial's URL fingerprint. */
    fun downloadUrl(itemId: String, fileId: String): String = http.policy.baseUrl.resolve("api/items/").newBuilder()
        .addPathSegment(itemId).addPathSegment("file").addPathSegment(fileId)
        .addPathSegment("download").build().toString()

    /**
     * A list thumbnail for the Library. The cover route needs no token in v2.38.0, so this
     * request carries no Authorization header. Only an image body within [THUMBNAIL_MAX_BYTES]
     * is accepted; a failure is recoverable and never blocks the catalog.
     */
    suspend fun coverThumbnail(itemId: String, widthPx: Int, updatedAt: Long): ServerResult<ByteArray> =
        cover(itemId, THUMBNAIL_MAX_BYTES) {
            addQueryParameter("width", widthPx.toString())
            addQueryParameter("format", "webp")
            addQueryParameter("ts", updatedAt.toString())
        }

    /** The original cover, stored with a downloaded edition. Same route, no token, raw bytes. */
    suspend fun coverOriginal(itemId: String): ServerResult<ByteArray> =
        cover(itemId, ORIGINAL_MAX_BYTES) { addQueryParameter("raw", "1") }

    private suspend fun cover(
        itemId: String, maxBytes: Long, query: HttpUrl.Builder.() -> Unit,
    ): ServerResult<ByteArray> {
        val url = http.policy.baseUrl.resolve("api/items/").newBuilder()
            .addPathSegment(itemId).addPathSegment("cover")
            .apply(query)
            .build()
        val result = http.executeFollowingRedirects(Request.Builder().url(url).get().build(), DiagnosticOperation.COVER)
        if (result is ServerResult.Failed) return result
        val response = (result as ServerResult.Ok).value
        return withContext(Dispatchers.IO) { response.use { readCover(it, maxBytes) } }
    }

    private fun readCover(response: Response, maxBytes: Long): ServerResult<ByteArray> {
        if (response.code == 404) return ServerResult.Failed(ServerError.NotFound)
        if (!response.isSuccessful) return ServerResult.Failed(ServerError.Unexpected(response.code, "HTTP ${response.code}"))
        val contentType = response.header("Content-Type")
        if (contentType == null || !contentType.startsWith("image/", ignoreCase = true)) {
            return ServerResult.Failed(ServerError.Unexpected(response.code, "Cover is not an image: $contentType"))
        }
        val bytes = try {
            response.body.source().use { source -> readCapped(source, maxBytes) }
        } catch (error: IOException) {
            return ServerResult.Failed(ServerError.Unexpected(response.code, "Could not read the cover: ${error.message}"))
        }
        return bytes?.let { ServerResult.Ok(it) }
            ?: ServerResult.Failed(ServerError.Unexpected(response.code, "Cover exceeds $maxBytes bytes"))
    }

    /** Reads at most [maxBytes] + 1 bytes, or null when the body is larger than [maxBytes]. */
    private fun readCapped(source: BufferedSource, maxBytes: Long): ByteArray? = try {
        source.readByteArray(maxBytes + 1).takeIf { it.size <= maxBytes }
    } catch (_: EOFException) {
        // Fewer bytes than the cap, so the whole body is within the limit.
        source.readByteArray()
    }

    private fun parseItem(item: JsonObject): ItemManifest {
        val media = item.required("media").jsonObject
        val metadata = media.required("metadata").jsonObject
        val audioFiles = media["audioFiles"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val filesById = audioFiles.groupBy { it.optionalString("ino") }
        val playableTracks = media["tracks"]?.jsonArray?.map { it.jsonObject }
            ?.filter { it["exclude"]?.jsonPrimitive?.content != "true" }.orEmpty()
        val tracks = playableTracks.mapIndexed { ordinal, track ->
            val fileId = track.optionalString("ino")?.takeIf(String::isNotBlank)
                ?: error("Track ${ordinal + 1} has no ino")
            val matches = filesById[fileId].orEmpty()
            if (matches.size != 1) error("Track ${ordinal + 1} ino $fileId has ${matches.size} audio-file matches")
            val file = matches.single()
            if (file["exclude"]?.jsonPrimitive?.content == "true") error("Excluded audio file $fileId is in play order")
            val fileMetadata = file.required("metadata").jsonObject
            val extension = fileMetadata.optionalString("ext")?.trimStart('.')?.takeIf(String::isNotBlank)
                ?: fileMetadata.optionalString("filename")?.substringAfterLast('.', "")
                ?: ""
            ManifestSourceTrack(ordinal, fileId, track.secondsMs("startOffset"),
                track.secondsMs("duration"), fileMetadata.optionalLong("size"),
                fileMetadata.optionalLong("mtimeMs"), extension.lowercase(),
                file.optionalString("codec"), file.optionalString("mimeType"))
        }
        return ItemManifest(item.string("id"), item.required("updatedAt").jsonPrimitive.long,
            metadata.string("title"), metadata.optionalString("authorName"),
            metadata.optionalString("narratorName"),
            metadata.optionalString("descriptionPlain") ?: metadata.optionalString("description"),
            item.string("mediaType"), tracks,
            media["chapters"]?.jsonArray.orEmpty().map { chapter ->
                val raw = chapter.jsonObject
                RawChapter(raw.secondsMs("start"), raw.optionalSecondsMs("end"), raw.optionalString("title"))
            }, media.optionalString("coverPath") != null)
    }

    private suspend fun items(access: String, libraryId: String, page: Int?, limit: Int): ServerResult<CatalogPage> {
        val url = http.policy.baseUrl.resolve("api/libraries/").newBuilder()
            .addPathSegment(libraryId).addPathSegment("items")
            .apply { if (page != null) addQueryParameter("page", page.toString()) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "media.metadata.title")
            .addQueryParameter("minified", "1").build()
        return send(Request.Builder().url(url).header("Authorization", "Bearer $access").get().build(),
            authorized = true, operation = DiagnosticOperation.CATALOG) { body ->
            val obj = body.jsonObject
            // Every entry is returned, playable or not: the refresh counts all of them against
            // `total` before it skips the ones without audio tracks.
            CatalogPage(obj.required("results").jsonArray.map { result ->
                val item = result.jsonObject
                val media = item.required("media").jsonObject
                val tracks = media["numTracks"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val metadata = media.required("metadata").jsonObject
                CatalogEntry(item.string("id"), metadata.string("title"), metadata.optionalString("authorName"),
                    metadata.optionalString("narratorName"), item.required("updatedAt").jsonPrimitive.long,
                    media["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                    media["size"]?.jsonPrimitive?.content?.toLongOrNull(), tracks)
            }, obj.required("total").jsonPrimitive.int)
        }
    }

    private fun request(path: String): Request.Builder = Request.Builder().url(http.policy.baseUrl.resolve(path))

    private suspend fun <T> get(
        path: String, access: String? = null, operation: DiagnosticOperation,
        decode: (JsonElement) -> T,
    ): ServerResult<T> {
        val builder = request(path).get()
        if (access != null) builder.header("Authorization", "Bearer $access")
        return send(builder.build(), authorized = access != null, decode = decode, operation = operation)
    }

    private suspend fun <T> send(
        request: Request, authorized: Boolean = false, login: Boolean = false, refresh: Boolean = false,
        operation: DiagnosticOperation, decode: (JsonElement) -> T,
    ): ServerResult<T> {
        val result = http.executeFollowingRedirects(request, operation)
        if (result is ServerResult.Failed) return result
        val response = (result as ServerResult.Ok).value
        // The body streams from the socket, which Android refuses on the main thread.
        return withContext(Dispatchers.IO) { response.use { decodeResponse(it, authorized, login, refresh, decode) } }
    }

    private fun <T> decodeResponse(
        response: Response, authorized: Boolean, login: Boolean, refresh: Boolean, decode: (JsonElement) -> T,
    ): ServerResult<T> {
        when (response.code) {
            401 -> if (login) return ServerResult.Failed(ServerError.InvalidCredentials)
                else if (authorized || refresh) return ServerResult.Failed(ServerError.SessionExpired)
            403 -> if (refresh || authorized) return ServerResult.Failed(
                if (refresh) ServerError.SessionExpired else ServerError.Forbidden)
            404 -> return ServerResult.Failed(ServerError.NotFound)
            // Retry-After may also be an HTTP date; only the seconds form is read.
            429 -> return ServerResult.Failed(ServerError.RateLimited(response.header("Retry-After")?.trim()?.toLongOrNull()))
        }
        if (!response.isSuccessful) return ServerResult.Failed(ServerError.Unexpected(response.code, "HTTP ${response.code}"))
        return try {
            val text = response.body.string()
            ServerResult.Ok(decode(json.parseToJsonElement(text)))
        } catch (error: Exception) {
            ServerResult.Failed(ServerError.Unexpected(response.code, "Invalid server response: ${error.message ?: error.javaClass.simpleName}"))
        }
    }

    private fun account(body: JsonElement): Account {
        val user = body.jsonObject.required("user").jsonObject
        val details = details(user)
        return Account(details.accountId, details.username, details.canDownload, details.librariesAccessible,
            TokenPair(user.string("accessToken"), user.string("refreshToken")))
    }

    private fun details(user: JsonObject): AccountDetails = AccountDetails(
        user.string("id"), user.string("username"),
        user.required("permissions").jsonObject.required("download").jsonPrimitive.boolean,
        user.required("librariesAccessible").jsonArray.map { it.jsonPrimitive.content },
    )

    private fun emptyBody() = ByteArray(0).toRequestBody(null)

    private companion object {
        const val THUMBNAIL_MAX_BYTES = 2L * 1024 * 1024
        const val ORIGINAL_MAX_BYTES = 20L * 1024 * 1024
    }
}

private fun JsonObject.required(key: String): JsonElement = this[key] ?: error("Missing $key")
private fun JsonObject.string(key: String): String = required(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.content?.takeUnless { it == "null" }
private fun JsonObject.optionalLong(key: String): Long? = this[key]?.jsonPrimitive?.content?.toLongOrNull()
private fun JsonObject.secondsMs(key: String): Long = required(key).jsonPrimitive.content.toDouble().times(1000).toLong()
private fun JsonObject.optionalSecondsMs(key: String): Long? = this[key]?.jsonPrimitive?.content
    ?.toDoubleOrNull()?.times(1000)?.toLong()
