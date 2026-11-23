package ch.lkmc.pageworn.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.catalog.CatalogRefresher
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.server.BaseUrlParse
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.server.MemoryTokenVault
import ch.lkmc.pageworn.server.NetworkDiagnostics
import ch.lkmc.pageworn.server.RouteMode
import ch.lkmc.pageworn.server.parseBaseUrl
import com.google.common.truth.Truth.assertThat
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A02: an address of another server stays unpaired, even for an account with the same ID. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PairingMismatchTest {
    private lateinit var db: PagewornDatabase
    private lateinit var server: MockWebServer
    private val vault = MemoryTokenVault()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun a02AnotherServerStaysUnpairedAndItsNewSessionIsRevoked() = runBlocking<Unit> {
        db.profiles().upsert(ProfileEntity(PROFILE, ACCOUNT, "listener", "library-of-the-first-server", null, "2.38.0", 0))
        val paired = AddressEntity("home", PROFILE, "https://home.invalid/", "Home", priority = 0)
        db.addresses().upsert(paired)
        listOf("login", "libraries", "logout").forEach { server.enqueue(MockResponse().setBody(fixture(it))) }
        val base = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl

        val result = accounts().signIn(CheckedAddress(base, "Away", true, null, false), "listener", "fixture-secret")

        assertThat(result).isEqualTo(SignInResult.PairingMismatch)
        assertThat(db.addresses().forProfile(PROFILE)).containsExactly(paired)
        assertThat(vault.addresses()).isEmpty()
        assertThat(List(server.requestCount) { server.takeRequest().path }).containsExactly("/abs/login", "/abs/api/libraries", "/abs/logout").inOrder()
    }

    private fun accounts(): AccountService {
        val localNetwork = LocalNetworkAccess(ApplicationProvider.getApplicationContext()) { emptyList() }
        val access = ServerAccess(
            db = db,
            vault = vault,
            localNetwork = localNetwork,
            sharedHttp = OkHttpClient(),
            platformTrust = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build().trustManager,
            nowMs = { NOW_MS },
            currentNetwork = { null },
            routeMode = { RouteMode.AUTOMATIC to null },
            diagnostics = NetworkDiagnostics(nowMs = { NOW_MS }),
        )
        return AccountService(db, vault, access, localNetwork, CatalogRefresher(db, { NOW_MS }), { NOW_MS })
    }

    private fun fixture(name: String): String {
        val source = requireNotNull(javaClass.classLoader!!.getResourceAsStream("abs/v2.38.0/$name.json"))
        return Json.parseToJsonElement(source.bufferedReader().use { it.readText() })
            .jsonObject.getValue("response").jsonObject.getValue("body").toString()
    }

    private companion object {
        const val PROFILE = "profile"
        const val ACCOUNT = "94e9c644-9550-40be-b886-11a1b4bc20ed"
        const val NOW_MS = 1_000L
    }
}
