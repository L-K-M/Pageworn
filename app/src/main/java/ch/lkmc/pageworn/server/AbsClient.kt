package ch.lkmc.pageworn.server

import ch.lkmc.pageworn.catalog.CatalogEntry
import ch.lkmc.pageworn.catalog.CatalogPage
import ch.lkmc.pageworn.catalog.RemoteLibrary
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

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
    suspend fun status(): ServerResult<ServerStatus> = get("status") { body ->
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
        return send(request, authorized = false, login = true) { account(it) }
    }

    suspend fun refresh(refreshToken: String): ServerResult<Account> = send(
        request("auth/refresh").header("x-refresh-token", refreshToken).post(emptyBody()).build(),
        authorized = false, refresh = true,
    ) { account(it) }

    suspend fun logout(refreshToken: String): ServerResult<Unit> = send(
        request("logout").header("x-refresh-token", refreshToken).post(emptyBody()).build(),
    ) { }

    suspend fun me(access: String): ServerResult<AccountDetails> = get("api/me", access) { body ->
        details(body.jsonObject)
    }

    suspend fun libraries(access: String): ServerResult<List<RemoteLibrary>> = get("api/libraries", access) { body ->
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

    private suspend fun items(access: String, libraryId: String, page: Int?, limit: Int): ServerResult<CatalogPage> {
        val url = http.policy.baseUrl.resolve("api/libraries/").newBuilder()
            .addPathSegment(libraryId).addPathSegment("items")
            .apply { if (page != null) addQueryParameter("page", page.toString()) }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "media.metadata.title")
            .addQueryParameter("minified", "1").build()
        return send(Request.Builder().url(url).header("Authorization", "Bearer $access").get().build(), authorized = true) { body ->
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

    private suspend fun <T> get(path: String, access: String? = null, decode: (JsonElement) -> T): ServerResult<T> {
        val builder = request(path).get()
        if (access != null) builder.header("Authorization", "Bearer $access")
        return send(builder.build(), authorized = access != null, decode = decode)
    }

    private suspend fun <T> send(
        request: Request, authorized: Boolean = false, login: Boolean = false, refresh: Boolean = false,
        decode: (JsonElement) -> T,
    ): ServerResult<T> {
        val result = http.executeFollowingRedirects(request)
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
}

private fun JsonObject.required(key: String): JsonElement = this[key] ?: error("Missing $key")
private fun JsonObject.string(key: String): String = required(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.content?.takeUnless { it == "null" }
