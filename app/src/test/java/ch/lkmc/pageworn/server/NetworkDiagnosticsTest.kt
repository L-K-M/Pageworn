package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test

class NetworkDiagnosticsTest {
    private val emptyTrust: X509TrustManager =
        HandshakeCertificates.Builder().addPlatformTrustedCertificates().build().trustManager

    private fun base(server: MockWebServer): BaseUrl =
        (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl

    private fun http(
        server: MockWebServer,
        diagnostics: NetworkDiagnostics,
        label: String = "Home",
    ): AddressHttp = AddressHttp(
        OkHttpClient(),
        AddressPolicy(base(server), cleartextOptIn = true, confirmedCertSha256 = null, passedNormalValidation = false),
        emptyTrust,
        diagnostics = diagnostics,
        addressLabel = label,
    )

    @Test fun recordsOnlyStructuredFieldsAndRendersOneLinePerEntry() {
        val diagnostics = NetworkDiagnostics(limit = 10, nowMs = { 1_000L })

        diagnostics.record("Home", DiagnosticOperation.CATALOG, DiagnosticStatus.OK, startedAtMillis = 900L)

        assertThat(diagnostics.entries()).containsExactly(
            NetworkDiagnostic("Home", DiagnosticOperation.CATALOG, DiagnosticStatus.OK, 100L, 1_000L)
        )
        assertThat(diagnostics.render()).isEqualTo("1000 Home catalog ok 100ms")
    }

    @Test fun boundedLogKeepsTheNewestEntries() {
        var now = 0L
        val diagnostics = NetworkDiagnostics(limit = 3, nowMs = { now })

        repeat(5) {
            now += 1_000L
            diagnostics.record("Home", DiagnosticOperation.REQUEST, DiagnosticStatus.UNREACHABLE, startedAtMillis = now - 1L)
        }

        assertThat(diagnostics.entries().map { it.atMillis }).containsExactly(3_000L, 4_000L, 5_000L).inOrder()
    }

    @Test fun aHostUsedAsTheDefaultAddressLabelIsRedacted() {
        val diagnostics = NetworkDiagnostics(nowMs = { 100L })
        diagnostics.record("localhost", DiagnosticOperation.STATUS, DiagnosticStatus.OK, host = "localhost")

        assertThat(diagnostics.render()).isEqualTo("100 (unlabeled) status ok 0ms")
    }

    @Test fun textFieldsCannotCarryAUrlPathQueryOrSeparator() {
        val raw = "https://host.example/abs/api/items?q=T0KEN&file=BOOK"
        val diagnostics = NetworkDiagnostics(nowMs = { 9L })
        diagnostics.record(raw, DiagnosticOperation.REQUEST, DiagnosticStatus.BLOCKED, startedAtMillis = 2L)
        val rendered = diagnostics.render()

        assertThat(rendered).doesNotContain("https://")
        assertThat(rendered).doesNotContain("host.example")
        assertThat(rendered).doesNotContain("T0KEN")
        assertThat(rendered).doesNotContain("BOOK")
        assertThat(rendered).doesNotContain("/abs")
        assertThat(rendered).doesNotContain("?q=")
        assertThat(rendered).doesNotContain("&file=")
        assertThat(rendered).doesNotContain("\\")

        assertThat(rendered).isEqualTo("9 (unlabeled) request blocked 7ms")
    }

    @Test fun transportFailureLeavesUrlQueryTokenHostAndExceptionMessageOutOfTheLog() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val token = "T0KEN-abc"
            val host = server.url("/").toString()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                throw IOException("$token ${chain.request().url.encodedPath} book-title.mp3 $host")
            }.build()
            val diagnostics = NetworkDiagnostics(nowMs = { 5_000L })
            val addressHttp = AddressHttp(
                client,
                AddressPolicy(base(server), cleartextOptIn = true, confirmedCertSha256 = null, passedNormalValidation = false),
                emptyTrust,
                diagnostics = diagnostics,
                addressLabel = "Home",
            )
            val request = Request.Builder()
                .url(server.url("/abs/api/items/BOOK/file/FILE/download?q=$token"))
                .build()

            val result = addressHttp.execute(request, operation = DiagnosticOperation.DOWNLOAD)

            assertThat(result).isInstanceOf(ServerResult.Failed::class.java)
            val rendered = diagnostics.render()
            assertThat(rendered).isEqualTo("5000 Home download unreachable 0ms")
            assertThat(rendered).doesNotContain(token)
            assertThat(rendered).doesNotContain("/abs")
            assertThat(rendered).doesNotContain("BOOK")
            assertThat(rendered).doesNotContain("IOException")
            assertThat(rendered).doesNotContain(host)
        }
    }

    @Test fun successfulRequestRecordsLabelOperationAndCategoryOnly() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            val token = "T0KEN-xyz"
            val diagnostics = NetworkDiagnostics(nowMs = { 6_000L })

            val result = http(server, diagnostics, label = "Tailscale")
                .execute(Request.Builder().url(server.url("/abs/status?token=$token")).build(), operation = DiagnosticOperation.STATUS)

            assertThat(result).isInstanceOf(ServerResult.Ok::class.java)
            (result as ServerResult.Ok).value.close()
            val rendered = diagnostics.render()
            assertThat(rendered).isEqualTo("6000 Tailscale status ok 0ms")
            assertThat(rendered).doesNotContain(token)
            assertThat(rendered).doesNotContain("/abs")
            assertThat(rendered).doesNotContain(server.hostName)
        }
    }

    @Test fun certificateFailureLeavesFingerprintAndHostOutOfTheLog() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("secret-host.example").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(certificates.sslSocketFactory(), false)
            server.start()
            val diagnostics = NetworkDiagnostics(nowMs = { 7_000L })

            val result = http(server, diagnostics)
                .execute(Request.Builder().url(server.url("/abs/status")).build(), operation = DiagnosticOperation.STATUS)

            assertThat((result as ServerResult.Failed).error).isInstanceOf(ServerError.Certificate::class.java)
            val rendered = diagnostics.render()
            assertThat(rendered).isEqualTo("7000 Home status certificate 0ms")
            assertThat(rendered).doesNotContain(sha256Fingerprint(certificate.certificate))
            assertThat(rendered).doesNotContain("secret-host.example")
        }
    }
}
