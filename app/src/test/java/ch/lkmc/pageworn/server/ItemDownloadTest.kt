package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Before
import org.junit.Test

class ItemDownloadTest {
    private lateinit var server: MockWebServer
    private val trust: X509TrustManager = HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates().build().trustManager

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    private fun client(): AbsClient {
        val base = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl
        return AbsClient(AddressHttp(OkHttpClient(), AddressPolicy(base, true, null, false), trust))
    }

    private fun fixture(name: String): String {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("abs/v2.38.0/$name.json"))
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
            .jsonObject.getValue("response").jsonObject.getValue("body").toString()
    }

    private fun item(name: String): ItemManifest = runBlocking {
        server.enqueue(MockResponse().setBody(fixture(name)))
        (client().item("access", "fixture") as ServerResult.Ok).value
    }

    @Test fun itemBoundaryBookHasTwoTracksInPlayOrder() {
        val item = item("item-boundary-book")
        assertThat(item.tracks.map { it.fileId }).containsExactly("1051823", "1051824").inOrder()
        assertThat(item.tracks.map { it.index }).containsExactly(0, 1).inOrder()
        assertThat(item.tracks.map { it.startOffsetMs }).containsExactly(0L, 90_000L).inOrder()
        assertThat(item.tracks.map { it.sizeBytes }).containsExactly(739453L, 986994L).inOrder()
        assertThat(item.tracks.first().extension).isEqualTo("m4a")
        assertThat(server.takeRequest().path).isEqualTo("/abs/api/items/fixture?expanded=1")
    }

    @Test fun itemExcludeTrackRenumbersPlayableTracks() {
        val item = item("item-exclude-track-book")
        assertThat(item.tracks.map { it.fileId }).containsExactly("1051818", "1051820").inOrder()
        assertThat(item.tracks.map { it.index }).containsExactly(0, 1).inOrder()
    }

    @Test fun itemMixedMediaDoesNotIncludeEpub() {
        val item = item("item-mixed-media-book")
        assertThat(item.tracks.map { it.fileId }).containsExactly("1051838")
        assertThat(item.tracks.single().mimeType).isEqualTo("audio/mp4")
    }

    @Test fun itemNightOrchardConvertsChaptersToMilliseconds() {
        val item = item("item-the-night-orchard")
        assertThat(item.coverPresent).isTrue()
        assertThat(item.chapters.map { it.startMs }).containsExactly(0L, 100_000L, 200_000L).inOrder()
        assertThat(item.chapters.map { it.endMs }).containsExactly(100_000L, 200_000L, 300_000L).inOrder()
    }

    @Test fun itemDuplicateInoIsUnexpected() = runBlocking {
        val body = fixture("item-boundary-book").replaceFirst("\"ino\":\"1051824\"", "\"ino\":\"1051823\"")
        server.enqueue(MockResponse().setBody(body))
        val result = client().item("access", "fixture")
        assertThat(result).isInstanceOf(ServerResult.Failed::class.java)
        assertThat((result as ServerResult.Failed).error).isInstanceOf(ServerError.Unexpected::class.java)
    }

    @Test fun downloadUsesPermissionCheckedRouteAndIdentityEncoding() = runBlocking {
        server.enqueue(MockResponse().setBody("audio"))
        val response = client().download("access", "item/id", "file/id", null)
        assertThat(response).isInstanceOf(ServerResult.Ok::class.java)
        (response as ServerResult.Ok).value.close()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/abs/api/items/item%2Fid/file/file%2Fid/download")
        assertThat(request.getHeader("Accept-Encoding")).isEqualTo("identity")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer access")
    }

    @Test fun downloadSendsDateIfRange() = runBlocking {
        server.enqueue(MockResponse().setBody("audio"))
        val date = "Sat, 21 Nov 2026 15:01:43 GMT"
        val response = client().download("access", "item", "file", ResumeRequest(3, date))
        (response as ServerResult.Ok).value.close()
        val request = server.takeRequest()
        assertThat(request.getHeader("Range")).isEqualTo("bytes=3-")
        assertThat(request.getHeader("If-Range")).isEqualTo(date)
    }

    @Test fun downloadRejectsWeakIfRange() {
        try {
            ResumeRequest(3, "W/\"weak\"")
            throw AssertionError("Weak validator accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun downloadForbiddenMapsToPermissionError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        assertThat(client().download("access", "item", "file", null))
            .isEqualTo(ServerResult.Failed(ServerError.Forbidden))
    }
}
