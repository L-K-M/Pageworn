package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test

class AddressHttpTest {
    private val emptyTrust: X509TrustManager = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build().trustManager

    private fun base(server: MockWebServer): BaseUrl =
        (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl

    private fun http(server: MockWebServer, pin: String? = null, trust: X509TrustManager = emptyTrust,
        cleartext: Boolean = true, passed: Boolean = false): AddressHttp =
        AddressHttp(OkHttpClient(), AddressPolicy(base(server), cleartext, pin, passed), trust)

    @Test fun outsidePathAndCleartextWithoutOptInSendNoRequests() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val address = http(server)
            val outside = Request.Builder().url(server.url("/other")) .build()
            assertThat(address.execute(outside)).isInstanceOf(ServerResult.Failed::class.java)
            assertThat((address.execute(outside) as ServerResult.Failed).error).isInstanceOf(ServerError.Blocked::class.java)
            val noCleartext = http(server, cleartext = false)
            val inside = Request.Builder().url(server.url("/abs/status")).build()
            assertThat((noCleartext.execute(inside) as ServerResult.Failed).error).isInstanceOf(ServerError.Blocked::class.java)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun redirectsOutsideOriginAndDowngradeAreBlocked() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://other.example/abs/secret"))
            val request = Request.Builder().url(server.url("/abs/status")).header("Authorization", "Bearer secret").build()
            assertThat((http(server).executeFollowingRedirects(request) as ServerResult.Failed).error)
                .isInstanceOf(ServerError.Blocked::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build().trustManager
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost/abs/secret"))
            val request = Request.Builder().url(server.url("/abs/status")).build()
            assertThat((http(server, trust = trust).executeFollowingRedirects(request) as ServerResult.Failed).error)
                .isInstanceOf(ServerError.Blocked::class.java)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test fun unrecognizedPinnedAndChangedCertificatesSendNoRejectedRequests() = runBlocking {
        val first = HeldCertificate.Builder().commonName("different.example").build()
        val second = HeldCertificate.Builder().commonName("another.example").build()
        val firstCertificates = HandshakeCertificates.Builder().heldCertificate(first).build()
        MockWebServer().use { server ->
            server.useHttps(firstCertificates.sslSocketFactory(), false)
            server.start()
            val fingerprint = sha256Fingerprint(first.certificate)
            val request = Request.Builder().url(server.url("/abs/status")).build()
            assertThat(http(server).execute(request)).isEqualTo(
                ServerResult.Failed(ServerError.Certificate(fingerprint, ServerError.Certificate.Kind.UNRECOGNIZED)))
            assertThat(server.requestCount).isEqualTo(0)

            server.enqueue(MockResponse().setBody("ok"))
            val confirmed = http(server, pin = fingerprint)
            val accepted = confirmed.execute(request) as ServerResult.Ok
            accepted.value.close()
            assertThat(server.requestCount).isEqualTo(1)
        }
        val secondCertificates = HandshakeCertificates.Builder().heldCertificate(second).build()
        MockWebServer().use { server ->
            server.useHttps(secondCertificates.sslSocketFactory(), false)
            server.start()
            val request = Request.Builder().url(server.url("/abs/status")).build()
            assertThat(http(server, pin = sha256Fingerprint(first.certificate)).execute(request)).isEqualTo(
                ServerResult.Failed(ServerError.Certificate(sha256Fingerprint(second.certificate), ServerError.Certificate.Kind.CHANGED)))
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test fun pinIsScopedToAddressAndNormalValidationIsReported() = runBlocking {
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val normalTrust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build().trustManager
        MockWebServer().use { first ->
            first.useHttps(serverCertificates.sslSocketFactory(), false)
            first.start()
            first.enqueue(MockResponse().setBody("ok"))
            val pinned = http(first, pin = sha256Fingerprint(cert.certificate))
            (pinned.execute(Request.Builder().url(first.url("/abs/status")).build()) as ServerResult.Ok).value.close()
            assertThat(pinned.passedNormalValidation).isFalse()
        }
        MockWebServer().use { second ->
            second.useHttps(serverCertificates.sslSocketFactory(), false)
            second.start()
            val request = Request.Builder().url(second.url("/abs/status")).build()
            assertThat(http(second).execute(request)).isEqualTo(ServerResult.Failed(
                ServerError.Certificate(sha256Fingerprint(cert.certificate), ServerError.Certificate.Kind.UNRECOGNIZED)))
            assertThat(second.requestCount).isEqualTo(0)
            second.enqueue(MockResponse().setBody("ok"))
            val trusted = http(second, trust = normalTrust)
            (trusted.execute(request) as ServerResult.Ok).value.close()
            assertThat(trusted.passedNormalValidation).isTrue()
        }
    }

    @Test fun changedCertificateOnSameOriginIsRejected() = runBlocking {
        val original = HeldCertificate.Builder().commonName("elsewhere.example").build()
        val replacement = HeldCertificate.Builder().commonName("replacement.example").build()
        val first = MockWebServer()
        first.useHttps(HandshakeCertificates.Builder().heldCertificate(original).build().sslSocketFactory(), false)
        first.start()
        val port = first.port
        val request = Request.Builder().url(first.url("/abs/status")).build()
        val address = http(first, pin = sha256Fingerprint(original.certificate))
        first.enqueue(MockResponse().addHeader("Connection", "close").setBody("ok"))
        (address.execute(request) as ServerResult.Ok).value.close()
        first.shutdown()

        MockWebServer().use { changed ->
            changed.useHttps(HandshakeCertificates.Builder().heldCertificate(replacement).build().sslSocketFactory(), false)
            changed.start(port)
            assertThat(address.execute(request)).isEqualTo(ServerResult.Failed(ServerError.Certificate(
                sha256Fingerprint(replacement.certificate), ServerError.Certificate.Kind.CHANGED)))
            assertThat(changed.requestCount).isEqualTo(0)
        }
    }
}
