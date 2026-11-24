package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test

class ServerClientTest {
    private lateinit var server: MockWebServer
    private val trust: X509TrustManager = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build().trustManager

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun client(): AbsClient {
        val base = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl
        return AbsClient(AddressHttp(OkHttpClient(), AddressPolicy(base, true, null, false), trust))
    }

    private fun fixture(name: String): String {
        val source = requireNotNull(javaClass.classLoader!!.getResourceAsStream("abs/v2.38.0/$name.json"))
        return Json.parseToJsonElement(source.bufferedReader().use { it.readText() }).jsonObject
            .getValue("response").jsonObject.getValue("body").toString()
    }

    private fun enqueueFixture(name: String) { server.enqueue(MockResponse().setBody(fixture(name))) }

    private fun imageResponse(bytes: ByteArray, contentType: String = "image/webp"): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(Buffer().write(bytes))

    @Test fun parseBaseUrlTable() {
        val cases = mapOf(
            "example.com" to "https://example.com/",
            "HTTPS://EXAMPLE.COM:443/abs///" to "https://example.com/abs/",
            "http://EXAMPLE.COM:80/a" to "http://example.com/a/",
            "https://EXAMPLE.COM:8443/a" to "https://example.com:8443/a/",
            // A host and a port without a scheme still default to HTTPS, whatever the host's letters.
            "books.example.com:8443" to "https://books.example.com:8443/",
            "localhost:13378/abs" to "https://localhost:13378/abs/",
            "192.168.1.5:13378" to "https://192.168.1.5:13378/",
            "LOCALHOST:13378" to "https://localhost:13378/",
            "[::1]:13378" to "https://[::1]:13378/",
        )
        for ((input, expected) in cases) {
            val actual = parseBaseUrl(input, true) as BaseUrlParse.Valid
            assertThat(actual.baseUrl.value).isEqualTo(expected)
        }
        val cleartext = parseBaseUrl("http://h/a", false) as BaseUrlParse.NeedsCleartextChoice
        assertThat(cleartext.baseUrl.origin).isEqualTo("http://h")
        for (input in listOf("https://user:pass@h", "https://h/a?q=1", "https://h/a#part", "ftp://h", "https://", "mailto:listener", "tel:1234567")) {
            assertThat(parseBaseUrl(input, true)).isInstanceOf(BaseUrlParse.Invalid::class.java)
        }
        val base = (parseBaseUrl("https://h/abs/", false) as BaseUrlParse.Valid).baseUrl
        assertThat(base.resolve("api/libraries").toString()).isEqualTo("https://h/abs/api/libraries")
        assertThat(base.contains(base.resolve("api/libraries"))).isTrue()
        assertThat(base.contains(base.resolve("../outside"))).isFalse()
    }

    @Test fun fingerprintUsesWholeCertificateAndOpenSslFormat() {
        val cert = HeldCertificate.Builder().commonName("other.host").build().certificate
        val fingerprint = sha256Fingerprint(cert)
        assertThat(fingerprint).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}")
        val keyHash = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
        assertThat(fingerprint).isNotEqualTo(keyHash)
    }

    @Test fun statusAndSignInBlockers() = runBlocking {
        enqueueFixture("status")
        val status = (client().status() as ServerResult.Ok).value
        assertThat(status.serverVersion).isEqualTo("2.38.0")
        assertThat(status.isInit).isTrue()
        assertThat(status.authMethods).containsExactly("local")
        assertThat(signInBlocker(status)).isNull()
        assertThat(signInBlocker(status.copy(serverVersion = "2.25.1"))).isEqualTo(ServerError.ServerTooOld("2.25.1"))
        assertThat(signInBlocker(status.copy(serverVersion = "2.38.0-beta"))).isNull()
        assertThat(signInBlocker(status.copy(authMethods = emptyList()))).isEqualTo(ServerError.NoPasswordSignIn)
        assertThat(server.takeRequest().path).isEqualTo("/abs/status")
    }

    @Test fun loginMapsFixtureAndInvalidCredentials() = runBlocking {
        enqueueFixture("login")
        val account = (client().login("listener", "secret") as ServerResult.Ok).value
        assertThat(account.accountId).isEqualTo("94e9c644-9550-40be-b886-11a1b4bc20ed")
        assertThat(account.username).isEqualTo("listener")
        assertThat(account.canDownload).isTrue()
        assertThat(account.tokens).isEqualTo(TokenPair("<access-token>", "<refresh-token>"))
        val request = server.takeRequest()
        assertThat(request.getHeader("x-return-tokens")).isEqualTo("true")
        assertThat(request.body.readUtf8()).contains("\"username\":\"listener\"")
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client().login("bad", "bad")).isEqualTo(ServerResult.Failed(ServerError.InvalidCredentials))
    }

    @Test fun responseBodiesAreReadOffTheCallersThread() {
        // Android refuses socket reads on the main thread. A body the caller reads itself works only
        // when OkHttp happened to buffer it already, so record the thread of every body read.
        val readers = Collections.synchronizedSet(mutableSetOf<Thread>())
        val shared = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = response.body
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    readers += Thread.currentThread()
                    return super.read(sink, byteCount)
                }
            }
            response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
        }.build()
        val base = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl
        val client = AbsClient(AddressHttp(shared, AddressPolicy(base, true, null, false), trust))
        enqueueFixture("login")

        // Coroutine debug mode renames threads, so compare the thread itself rather than its name.
        val callerThread = AtomicReference<Thread>()
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller-main").also(callerThread::set) }
        try {
            val result = runBlocking(caller.asCoroutineDispatcher()) { client.login("listener", "secret") }
            assertThat(result).isInstanceOf(ServerResult.Ok::class.java)
        } finally {
            caller.shutdown()
        }
        assertThat(readers).isNotEmpty()
        assertThat(readers).doesNotContain(callerThread.get())
    }

    @Test fun spentAuthenticationLimitIsNeitherWrongCredentialsNorAnExpiredSession() = runBlocking {
        // v2.38.0 allows 40 sign-ins and renewals per 10 minutes from one client, then answers 429.
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "200").setBody(fixture("login-rate-limited")))
        }
        assertThat(client().login("listener", "secret")).isEqualTo(ServerResult.Failed(ServerError.RateLimited(200)))
        assertThat(client().refresh("refresh")).isEqualTo(ServerResult.Failed(ServerError.RateLimited(200)))
    }

    @Test fun librariesAndMeUseFixtureFields() = runBlocking {
        enqueueFixture("libraries")
        val libraries = (client().libraries("token") as ServerResult.Ok).value
        assertThat(libraries.map { it.name }).containsExactly("Audiobooks", "Second shelf").inOrder()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer token")
        enqueueFixture("me")
        val me = (client().me("token") as ServerResult.Ok).value
        assertThat(me.accountId).isEqualTo("94e9c644-9550-40be-b886-11a1b4bc20ed")
        assertThat(me.canDownload).isTrue()
    }

    @Test fun itemsPageMapsFixtureAndRequiresPositiveLimit() = runBlocking {
        enqueueFixture("items-page0")
        val client = client()
        val page = (client.itemsPage("token", "library", 0, 3) as ServerResult.Ok).value
        assertThat(page.total).isEqualTo(9)
        assertThat(page.entries.map { it.title }).containsExactly("Boundary Book", "Exclude Track Book", "Field Notes on Rain").inOrder()
        assertThat(page.entries[0].durationMs).isEqualTo(210000)
        assertThat(page.entries[0].sizeBytes).isEqualTo(1726447)
        assertThat(page.entries[0].numTracks).isEqualTo(2)
        val addedAt = Regex(""""addedAt":\s*(\d+)""").find(fixture("items-page0"))!!.groupValues[1].toLong()
        assertThat(page.entries[0].addedAt).isEqualTo(addedAt)
        // Entries without audio stay in the page so the refresh can count them against total.
        val withoutTracks = Regex(""""numTracks":\s*\d+""").replace(fixture("items-page0"), "\"numTracks\": 0")
        server.enqueue(MockResponse().setBody(withoutTracks))
        assertThat((client.itemsPage("token", "library", 0, 3) as ServerResult.Ok).value.entries.map { it.numTracks }).containsExactly(0, 0, 0)
        server.takeRequest()
        // The minified listing carries the description; the plain form wins over the HTML one.
        val described = Regex(""""description"\s*:\s*null""").replace(fixture("items-page0"), """"description": "<p>A tale</p>", "descriptionPlain": "A tale"""")
        server.enqueue(MockResponse().setBody(described))
        assertThat((client.itemsPage("token", "library", 0, 3) as ServerResult.Ok).value.entries[0].description).isEqualTo("A tale")
        server.takeRequest()
        // Older servers carry only the HTML form; its tags are stripped so no markup reaches the UI.
        val htmlOnly = Regex(""""description"\s*:\s*null""").replace(fixture("items-page0"), """"description": "<p>A <b>tale</b></p>"""")
        server.enqueue(MockResponse().setBody(htmlOnly))
        assertThat((client.itemsPage("token", "library", 0, 3) as ServerResult.Ok).value.entries[0].description).isEqualTo("A tale")
        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/abs/api/libraries/library/items?page=0&limit=3&sort=media.metadata.title&minified=1")
        try {
            client.itemsPage("token", "library", 0, 0)
            throw AssertionError("Expected invalid limit")
        } catch (_: IllegalArgumentException) { }
        enqueueFixture("items-limit0")
        assertThat((client.allItems("token", "library") as ServerResult.Ok).value.total).isEqualTo(9)
        assertThat(server.takeRequest().path).isEqualTo("/abs/api/libraries/library/items?limit=0&sort=media.metadata.title&minified=1")
    }

    @Test fun logoutSendsRefreshHeaderWithoutAccessHeader() = runBlocking {
        enqueueFixture("logout")
        assertThat(client().logout("refresh")).isEqualTo(ServerResult.Ok(Unit))
        val request = server.takeRequest()
        assertThat(request.getHeader("x-refresh-token")).isEqualTo("refresh")
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test fun refreshMapsRotatedAccountAndSendsRefreshHeader() = runBlocking {
        enqueueFixture("refresh")
        val account = (client().refresh("old-refresh") as ServerResult.Ok).value
        assertThat(account.accountId).isEqualTo("94e9c644-9550-40be-b886-11a1b4bc20ed")
        assertThat(account.tokens).isEqualTo(TokenPair("<access-token>", "<refresh-token>"))
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/abs/auth/refresh")
        assertThat(request.getHeader("x-refresh-token")).isEqualTo("old-refresh")
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test fun coverThumbnailSendsNoTokenAndTheExpectedQuery() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(1, 2, 3)))
        val cover = client().coverThumbnail("24ce6539-29bf-4b23-b239-22a38a406464", 200, 1795273325727)
        assertThat((cover as ServerResult.Ok).value.asList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo(
            "/abs/api/items/24ce6539-29bf-4b23-b239-22a38a406464/cover?width=200&format=webp&ts=1795273325727")
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test fun coverPathEncodesTheItemId() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(1)))
        client().coverThumbnail("item 1", 200, 1)
        assertThat(server.takeRequest().path).isEqualTo("/abs/api/items/item%201/cover?width=200&format=webp&ts=1")
    }

    @Test fun coverThumbnailRejectsNonImageAndOversizeBodies() = runBlocking {
        val client = client()
        server.enqueue(imageResponse("<html>".toByteArray(), "text/html"))
        val nonImage = client.coverThumbnail("item-1", 200, 1) as ServerResult.Failed
        assertThat(nonImage.error).isInstanceOf(ServerError.Unexpected::class.java)
        server.enqueue(imageResponse(ByteArray(2 * 1024 * 1024 + 1)))
        val oversize = client.coverThumbnail("item-1", 200, 1) as ServerResult.Failed
        assertThat(oversize.error).isInstanceOf(ServerError.Unexpected::class.java)
    }

    @Test fun coverThumbnailMaps404ToNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client().coverThumbnail("item-1", 200, 1))
            .isEqualTo(ServerResult.Failed(ServerError.NotFound))
        assertThat(server.takeRequest().getHeader("Authorization")).isNull()
    }

    @Test fun coverOriginalRequestsRawWithoutAToken() = runBlocking {
        server.enqueue(imageResponse(byteArrayOf(9, 8)))
        val cover = client().coverOriginal("item-1")
        assertThat((cover as ServerResult.Ok).value.asList()).containsExactly(9.toByte(), 8.toByte()).inOrder()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/abs/api/items/item-1/cover?raw=1")
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test fun authorizedErrorsAndInvalidBodyAreExplicit() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client.me("token")).isEqualTo(ServerResult.Failed(ServerError.SessionExpired))
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(client.me("token")).isEqualTo(ServerResult.Failed(ServerError.Forbidden))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.me("token")).isEqualTo(ServerResult.Failed(ServerError.NotFound))
        server.enqueue(MockResponse().setBody("not json"))
        val invalid = client.me("token") as ServerResult.Failed
        assertThat(invalid.error).isInstanceOf(ServerError.Unexpected::class.java)
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(client.refresh("refresh")).isEqualTo(ServerResult.Failed(ServerError.SessionExpired))
    }
}
