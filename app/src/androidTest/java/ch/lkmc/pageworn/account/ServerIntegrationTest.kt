package ch.lkmc.pageworn.account

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.catalog.CatalogRefresher
import ch.lkmc.pageworn.catalog.ListingStatus
import ch.lkmc.pageworn.catalog.RefreshOutcome
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.server.AddressPolicy
import ch.lkmc.pageworn.server.BaseUrl
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import ch.lkmc.pageworn.testing.TestApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M2 exit check against the local Audiobookshelf v2.38.0 reference instance, reached from the
 * emulator over two origins of one server: `http://10.0.2.2:13378/` ("Home", cleartext opt-in) and
 * `https://10.0.2.2:13443/` ("Tailscale", the TLS front with a self-signed certificate).
 *
 * The phases run in order as separate instrumentation runs, because the host changes the world
 * between them (it swaps the TLS certificate, or revokes the local-network permission, which
 * kills the app). `server-fixture/m2-check.sh` runs them and passes the disposable accounts'
 * credentials and the TLS front's fingerprint as instrumentation arguments; nothing secret is in
 * the repository. Without those arguments every phase skips itself.
 */
@RunWith(AndroidJUnit4::class)
class ServerIntegrationTest {
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val container get() = TestApp.container
    private val db get() = container.database

    private fun arg(name: String): String = args.getString(name).also { assumeTrue("needs -e $name", it != null) }!!

    /** An account service over the container's state, walking the catalog four items per page. */
    private fun service() = AccountService(
        container.database, container.tokenVault, container.serverAccess, container.localNetwork,
        CatalogRefresher(container.database, { System.currentTimeMillis() }, pageSize = PAGE_SIZE), { System.currentTimeMillis() },
    )

    private suspend fun AccountService.checked(url: String, label: String, trust: String? = null): CheckedAddress =
        when (val r = checkAddress(url, label, allowCleartext = url.startsWith("http:"), trustedSha256 = trust)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> throw AssertionError("address $url: ${r.problem}")
        }

    private suspend fun profileId() = db.profiles().current()!!.profileId

    private suspend fun address(label: String) = db.addresses().forProfile(profileId()).single { it.label == label }

    private suspend fun audiobooks() = db.libraries().forProfile(profileId()).single { it.name == "Audiobooks" }

    // --- Phase 1 --------------------------------------------------------------------------------

    @Test
    fun phase1SignInBrowseEveryPageAndPairTwoAddresses() = runBlocking {
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        val browser = arg("absBrowser")
        val browserPassword = arg("absBrowserPassword")
        val tlsSha256 = arg("tlsSha256")
        TestApp.reset()
        container.tokenVault.deleteAll()
        container.settings.pinnedAddressId = null
        val service = service()

        // Incorrect credentials: their own message, and no partial profile.
        val home = service.checked(HOME, "Home")
        assertThat(service.signIn(home, listener, "not-the-password")).isEqualTo(SignInResult.InvalidCredentials)
        assertThat(db.profiles().current()).isNull()
        assertThat(db.addresses().forProfile("")).isEmpty()

        // A non-admin account signs in and browses every page of the catalog.
        val connected = service.signIn(home, listener, password)
        assertThat(connected).isInstanceOf(SignInResult.Connected::class.java)
        assertThat(service.refreshLibraries()).isTrue()
        val libraries = db.libraries().forProfile(profileId())
        assertThat(libraries.map { it.name }).containsExactly("Audiobooks", "Second shelf")
        val outcome = service.refreshCatalog(audiobooks().libraryId)
        assertThat(outcome).isInstanceOf(RefreshOutcome.Published::class.java)
        val listing = CatalogRefresher(db, { 0 }).observeListing(profileId(), audiobooks().libraryId, "").first()
        assertThat(listing.status).isEqualTo(ListingStatus.READY)
        // Nine books over three pages of four; the two equal titles stay two records.
        assertThat(listing.rows).hasSize(9)
        val sameTitle = listing.rows.filter { it.title == "Same Title" }
        assertThat(sameTitle.map { it.itemId }.toSet()).hasSize(2)
        assertThat(listing.rows.map { it.title }).isInOrder(String.CASE_INSENSITIVE_ORDER)

        // An unrecognized certificate: its fingerprint, and no request until the listener trusts it.
        val unrecognized = service.checkAddress(TAILSCALE, "Tailscale", allowCleartext = false, trustedSha256 = null)
        assertThat(unrecognized).isEqualTo(Outcome.Err(AddressProblem.Certificate(tlsSha256, ServerError.Certificate.Kind.UNRECOGNIZED)))
        val tailscale = service.checked(TAILSCALE, "Tailscale", trust = tlsSha256)

        // Another account on the second address is refused and changes nothing.
        assertThat(service.signIn(tailscale, browser, browserPassword)).isEqualTo(SignInResult.DifferentAccount(listener))
        assertThat(db.addresses().forProfile(profileId())).hasSize(1)

        // The same account pairs after one confirmation.
        val pairing = service.signIn(tailscale, listener, password)
        assertThat(pairing).isInstanceOf(SignInResult.ConfirmPairing::class.java)
        assertThat((pairing as SignInResult.ConfirmPairing).libraryName).isEqualTo("Audiobooks")
        service.confirmPairing(pairing.pairing)
        assertThat(db.addresses().forProfile(profileId()).sortedBy { it.priority }.map { it.label }).containsExactly("Home", "Tailscale").inOrder()

        // Switching addresses keeps one book record: a saved place made through Home survives a
        // refresh through Tailscale, and the catalog keeps the same keys.
        val boundary = listing.rows.single { it.title == "Boundary Book" }
        val key = BookKey(profileId(), boundary.itemId)
        container.progressStore.saveOutsideSession(key, 115_000, 210_000, null, StopKind.ORDINARY)
        val keysBefore = listing.rows.map { it.itemId }.toSet()
        service.setEnabled(address("Home").addressId, false)
        assertThat(service.refreshCatalog(audiobooks().libraryId)).isInstanceOf(RefreshOutcome.Published::class.java)
        assertThat(address("Tailscale").lastSuccessAt).isNotNull()
        val after = CatalogRefresher(db, { 0 }).observeListing(profileId(), audiobooks().libraryId, "").first()
        assertThat(after.rows.map { it.itemId }.toSet()).isEqualTo(keysBefore)
        assertThat(container.progressStore.read(key)!!.positionMs).isEqualTo(115_000)
        service.setEnabled(address("Home").addressId, true)
    }

    // --- Phase 2: the host restarted the TLS front with another certificate ----------------------

    @Test
    fun phase2ChangedCertificate() = runBlocking {
        val newSha256 = arg("tlsSha256")
        assumeTrue(db.profiles().current() != null)
        val service = service()
        val library = audiobooks().libraryId
        val oldSha256 = address("Tailscale").confirmedCertSha256

        // With Home disabled, nothing else can serve: Library shows the certificate-changed copy
        // over its cached rows, and nothing is sent to the address.
        service.setEnabled(address("Home").addressId, false)
        assertThat(service.refreshCatalog(library)).isInstanceOf(RefreshOutcome.Failed::class.java)
        assertThat(service.notice.value).isEqualTo(ServerNotice.CertificateChanged("Tailscale"))
        val changed = address("Tailscale")
        assertThat(changed.certificateState).isEqualTo(CertificateState.CHANGED)
        assertThat(changed.presentedCertSha256).isEqualTo(newSha256)
        assertThat(changed.confirmedCertSha256).isEqualTo(oldSha256)
        assertThat(CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows).hasSize(9)

        // With Home reachable, automatic mode uses it and Library loads.
        service.setEnabled(address("Home").addressId, true)
        assertThat(service.refreshCatalog(library)).isInstanceOf(RefreshOutcome.Published::class.java)
        assertThat(service.notice.value).isNull()

        // Trusting the new certificate in Server addresses restores the address.
        service.trustPresentedCertificate(address("Tailscale").addressId)
        service.setEnabled(address("Home").addressId, false)
        assertThat(service.refreshCatalog(library)).isInstanceOf(RefreshOutcome.Published::class.java)
        assertThat(address("Tailscale").confirmedCertSha256).isEqualTo(newSha256)
        service.setEnabled(address("Home").addressId, true)

        // The first confirmation never extends to another origin: the same server and
        // certificate on a different origin (a host alias of the TLS front) is not trusted.
        val otherOrigin = container.serverAccess.transient(AddressPolicy(BaseUrl(TAILSCALE_OTHER_ORIGIN), false, null, false), "probe")
        val probe = otherOrigin.client.status()
        assertThat(probe).isInstanceOf(ServerResult.Failed::class.java)
        assertThat((probe as ServerResult.Failed).error).isInstanceOf(ServerError.Certificate::class.java)
    }

    // --- Phase 3: sign out and in again ------------------------------------------------------------

    @Test
    fun phase3SignOutThenSignIn() = runBlocking {
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        val browser = arg("absBrowser")
        val browserPassword = arg("absBrowserPassword")
        val tlsSha256 = arg("tlsSha256")
        assumeTrue(db.profiles().current() != null)
        val service = service()
        val library = audiobooks().libraryId
        val tokensBefore = db.addresses().forProfile(profileId()).associate { it.label to container.tokenVault.read(it.addressId)!! }
        val rowsBefore = CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows

        service.signOut()

        // Every credential is gone; the server revoked both sessions.
        db.addresses().forProfile(profileId()).forEach {
            assertThat(container.tokenVault.read(it.addressId)).isNull()
            assertThat(it.sessionState).isEqualTo(SessionState.SIGNED_OUT)
        }
        val home = service.checked(HOME, "Home")
        val probe = container.serverAccess.transient(AddressPolicy(home.baseUrl, true, null, false), "probe").client
        for ((label, tokens) in tokensBefore) {
            assertWithMessage("refresh token of $label after sign-out").that(probe.refresh(tokens.refreshToken)).isEqualTo(ServerResult.Failed(ServerError.SessionExpired))
        }

        // Library: the session-expired copy over the cached rows.
        assertThat(service.refreshCatalog(library)).isInstanceOf(RefreshOutcome.Failed::class.java)
        assertThat(service.notice.value).isEqualTo(ServerNotice.SignedOut)
        assertThat(CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows).isEqualTo(rowsBefore)

        // A different account is refused and changes nothing.
        assertThat(service.signIn(home, browser, browserPassword)).isEqualTo(SignInResult.DifferentAccount(listener))
        assertThat(db.addresses().forProfile(profileId()).all { it.sessionState == SessionState.SIGNED_OUT }).isTrue()

        // The original account signs in on Home; the catalog refreshes and the saved place holds.
        assertThat(service.signIn(home, listener, password)).isInstanceOf(SignInResult.Connected::class.java)
        assertThat(service.refreshCatalog(library)).isInstanceOf(RefreshOutcome.Published::class.java)
        val boundary = rowsBefore.single { it.title == "Boundary Book" }
        assertThat(container.progressStore.read(BookKey(profileId(), boundary.itemId))!!.positionMs).isEqualTo(115_000)

        // Tailscale needs its own sign-in, as when it was paired.
        assertThat(address("Tailscale").sessionState).isEqualTo(SessionState.SIGNED_OUT)
        val tailscale = service.checked(TAILSCALE, "Tailscale", trust = tlsSha256)
        assertThat(service.signIn(tailscale, listener, password)).isInstanceOf(SignInResult.Connected::class.java)
        assertThat(address("Tailscale").sessionState).isEqualTo(SessionState.SIGNED_IN)
        assertThat(db.addresses().forProfile(profileId())).hasSize(2)
    }

    // --- Phase 4: the host revoked the local-network permission -----------------------------------

    @Test
    fun phase4LocalNetworkDenied() = runBlocking {
        assumeTrue(db.profiles().current() != null)
        val local = container.localNetwork
        val service = service()
        if (!local.applies) {
            // Android 14 to 16: no such permission; the address connects without a request.
            assertThat(service.checkAddress(HOME, "Home", allowCleartext = true, trustedSha256 = null)).isInstanceOf(Outcome.Ok::class.java)
            return@runBlocking
        }
        assertWithMessage("the host revokes the permission before this phase").that(local.granted()).isFalse()
        assertThat(local.isLocal("10.0.2.2")).isTrue()

        // Observation for the plan: does Android 17 block the emulator's host alias without the permission?
        val blocked = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 13378), 2_000) } }.isFailure
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", "raw connect to 10.0.2.2 without ACCESS_LOCAL_NETWORK blocked=$blocked") })

        // Connection setup asks for the permission before the first contact.
        assertThat(service.checkAddress(HOME, "Home", allowCleartext = true, trustedSha256 = null))
            .isEqualTo(Outcome.Err(AddressProblem.LocalNetworkPermission("10.0.2.2", denied = false)))

        // Library: the local-network copy for the first blocked address in priority order, never
        // the unreachable copy, over the cached rows.
        val library = audiobooks().libraryId
        assertThat(service.refreshCatalog(library)).isInstanceOf(RefreshOutcome.Failed::class.java)
        assertThat(service.notice.value).isEqualTo(ServerNotice.LocalNetworkDenied("Home"))
        assertThat(CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows).hasSize(9)
    }

    private companion object {
        const val PAGE_SIZE = 4
        const val HOME = "http://10.0.2.2:13378/"
        const val TAILSCALE = "https://10.0.2.2:13443/"

        /** The emulator's other name for the host loopback: same TLS front, another origin. */
        const val TAILSCALE_OTHER_ORIGIN = "https://10.0.2.2:13444/"
    }
}
