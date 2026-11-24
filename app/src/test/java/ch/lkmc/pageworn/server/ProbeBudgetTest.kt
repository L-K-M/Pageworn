package ch.lkmc.pageworn.server

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Before
import org.junit.Test

/** docs/04: a reachability probe gets a short bounded timeout per address. */
class ProbeBudgetTest {
    private lateinit var server: MockWebServer
    private val trust: X509TrustManager = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build().trustManager

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun client(connectTimeoutMs: Long): AbsClient {
        val base = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl
        return AbsClient(AddressHttp(OkHttpClient(), AddressPolicy(base, true, null, false), trust, connectTimeoutMs = connectTimeoutMs))
    }

    @Test fun aServerThatAcceptsButNeverAnswersFailsTheProbeWithinItsBudget() = runBlocking {
        // A frozen proxy completes the TCP handshake and then says nothing.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val started = System.nanoTime()
        val result = client(connectTimeoutMs = 200).status()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertThat((result as ServerResult.Failed).error).isInstanceOf(ServerError.Unreachable::class.java)
        // The probe's budget is 400 ms; the read timeout alone would wait ten seconds.
        assertThat(elapsedMs).isLessThan(3_000)
    }

    @Test fun otherRequestsStillWaitForASlowAnswer() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(800, TimeUnit.MILLISECONDS).setBody(fixture("login")))

        val result = client(connectTimeoutMs = 200).login("listener", "fixture-secret")

        assertThat(result).isInstanceOf(ServerResult.Ok::class.java)
    }

    private fun fixture(name: String): String {
        val source = requireNotNull(javaClass.classLoader!!.getResourceAsStream("abs/v2.38.0/$name.json"))
        return Json.parseToJsonElement(source.bufferedReader().use { it.readText() }).jsonObject
            .getValue("response").jsonObject.getValue("body").toString()
    }
}
