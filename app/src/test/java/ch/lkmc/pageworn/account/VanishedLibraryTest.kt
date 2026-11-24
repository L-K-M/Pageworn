package ch.lkmc.pageworn.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.catalog.CatalogRefresher
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.LibraryEntity
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** A library the listener picked from a stale selector can vanish in the refresh that precedes its catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VanishedLibraryTest {
    private lateinit var db: PagewornDatabase
    private lateinit var server: MockWebServer
    private val vault = MemoryTokenVault()
    private val paths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                return when {
                    path.startsWith("/abs/status") -> MockResponse().setBody(fixture("status"))
                    path == "/abs/api/libraries" -> MockResponse().setBody(fixture("libraries"))
                    path.startsWith("/abs/api/me") -> MockResponse().setBody(fixture("me"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun aLibraryRemovedOnTheServerFallsBackToTheRemainingOne() = runBlocking<Unit> {
        db.profiles().upsert(ProfileEntity(PROFILE, "account", "Listener", VANISHED, VANISHED, "2.38.0", 1))
        db.libraries().upsertAll(listOf(LibraryEntity(PROFILE, VANISHED, "Old shelf", 0), LibraryEntity(PROFILE, AUDIOBOOKS, "Audiobooks", 1)))
        val baseUrl = (parseBaseUrl(server.url("/abs/").toString(), true) as BaseUrlParse.Valid).baseUrl.value
        db.addresses().upsert(AddressEntity("home", PROFILE, baseUrl, "Home", 0, cleartextOptIn = true))
        vault.write("home", TokenPair("access", "refresh"))

        // The selector still lists the old library; the server no longer does.
        val service = accounts()
        val refreshed = service.refreshLibraryAndCatalog(VANISHED)

        // A second stale tap can arrive just after the refresh deletes its library.
        service.chooseLibrary(VANISHED)

        assertThat(refreshed).isTrue()
        assertThat(db.libraries().get(PROFILE, VANISHED)).isNull()
        assertThat(db.profiles().current()?.chosenLibraryId).isEqualTo(AUDIOBOOKS)
        // The catalog is read from the library that remains, never from the vanished one.
        assertThat(paths.any { it.startsWith("/abs/api/libraries/$AUDIOBOOKS/") }).isTrue()
        assertThat(paths.none { VANISHED in it }).isTrue()
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
        const val VANISHED = "vanished-library"
        /** The first book library in the captured `libraries` fixture. */
        const val AUDIOBOOKS = "560a911a-be0e-4039-b397-811a9face6e8"
        const val NOW_MS = 1_000_000L
    }
}
