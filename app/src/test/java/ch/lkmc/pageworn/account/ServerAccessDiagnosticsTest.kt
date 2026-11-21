package ch.lkmc.pageworn.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.server.BaseUrlParse
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.server.MemoryTokenVault
import ch.lkmc.pageworn.server.NetworkDiagnostics
import ch.lkmc.pageworn.server.RouteMode
import ch.lkmc.pageworn.server.TokenPair
import ch.lkmc.pageworn.server.parseBaseUrl
import com.google.common.truth.Truth.assertThat
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A23: routing binds each redacted entry to its address label and never to the host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerAccessDiagnosticsTest {
    private lateinit var db: PagewornDatabase
    private lateinit var server: MockWebServer
    private val vault = MemoryTokenVault()
    private var clock = 1_000L
    private lateinit var diagnostics: NetworkDiagnostics

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        server = MockWebServer()
        server.start()
        clock = 1_000L
        diagnostics = NetworkDiagnostics(nowMs = { clock })
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun access(): ServerAccess {
        val trust: X509TrustManager =
            HandshakeCertificates.Builder().addPlatformTrustedCertificates().build().trustManager
        return ServerAccess(
            db = db,
            vault = vault,
            localNetwork = LocalNetworkAccess(ApplicationProvider.getApplicationContext()) { emptyList() },
            sharedHttp = OkHttpClient(),
            platformTrust = trust,
            nowMs = { clock },
            currentNetwork = { null },
            routeMode = { RouteMode.AUTOMATIC to null },
            diagnostics = diagnostics,
        )
    }

    private suspend fun addAddress(addressId: String, baseUrl: String, label: String, priority: Int = 0) {
        db.profiles().upsert(ProfileEntity("profile", "account", "Listener", null, null, "2.38.0", 0))
        db.addresses().upsert(AddressEntity(addressId, "profile", baseUrl, label, priority, cleartextOptIn = true))
        vault.write(addressId, TokenPair("access", "refresh"))
    }

    private fun baseUrl(server: MockWebServer): String =
        (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl.value

    private fun statusResponse(): MockResponse = MockResponse()
        .setBody("""{"serverVersion":"2.38.0","isInit":false,"authMethods":["local"]}""")

    @Test fun routedRequestIsLoggedUnderItsAddressLabelWithoutHostOrPath() = runBlocking {
        addAddress("addr", baseUrl(server), "Home")
        // The selector probes the address, then the routed work asks it again.
        server.enqueue(statusResponse())
        server.enqueue(statusResponse())

        val routed = access().route { it.client.status() }

        assertThat(routed).isInstanceOf(Routed.Done::class.java)
        val rendered = diagnostics.render()
        assertThat(rendered).contains("Home status ok")
        assertThat(rendered).doesNotContain("/abs")
        assertThat(rendered).doesNotContain(server.hostName)
        assertThat(rendered).doesNotContain("127.0.0.1")
    }

    @Test fun failoverLogsEachAddressByItsOwnLabel() = runBlocking {
        MockWebServer().use { failed ->
            failed.start()
            failed.enqueue(MockResponse().setResponseCode(500))
            addAddress("old", baseUrl(failed), "Old route", priority = 0)
            addAddress("new", baseUrl(server), "New route", priority = 1)
            server.enqueue(statusResponse())
            server.enqueue(statusResponse())

            val routed = access().route { it.client.status() }

            assertThat(routed).isInstanceOf(Routed.Done::class.java)
            val rendered = diagnostics.render()
            assertThat(rendered).contains("Old route status unexpected")
            assertThat(rendered).contains("New route status ok")
            assertThat(rendered).doesNotContain("/abs")
            assertThat(rendered).doesNotContain(failed.hostName)
        }
    }

    @Test fun unusableAddressLogsTheRoutingCategoryWithoutContactingIt() = runBlocking {
        db.profiles().upsert(ProfileEntity("profile", "account", "Listener", null, null, "2.38.0", 0))
        db.addresses().upsert(
            AddressEntity(
                addressId = "addr", profileId = "profile", baseUrl = "https://home.example/abs/",
                label = "Home", priority = 0,
                certificateState = CertificateState.UNRECOGNIZED, presentedCertSha256 = "AA:BB",
            )
        )

        val routed = access().route { it.client.status() }

        assertThat(routed).isInstanceOf(Routed.Unavailable::class.java)
        val rendered = diagnostics.render()
        assertThat(rendered).contains("Home route certificate")
        assertThat(rendered).doesNotContain("home.example")
        assertThat(server.requestCount).isEqualTo(0)
    }
}
