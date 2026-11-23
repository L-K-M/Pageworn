package ch.lkmc.pageworn.account

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.catalog.CatalogRefresher
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.catalog.ListingStatus
import ch.lkmc.pageworn.catalog.RefreshOutcome
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.server.AddressPolicy
import ch.lkmc.pageworn.server.BaseUrl
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import ch.lkmc.pageworn.testing.Fixtures
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
 * `https://10.0.2.2:13443/` ("Tailscale", the TLS front with a self-signed certificate). Phase 5
 * adds `https://10.0.2.2:13445/` ("Office"), whose certificate comes from a fixture authority the
 * host installs in the emulator's user store.
 *
 * The phases run in order as separate instrumentation runs, because the host changes the world
 * between them (it swaps the TLS certificate, revokes the local-network permission, which
 * kills the app, or installs an authority). `server-fixture/m2-check.sh` runs them and passes the disposable accounts'
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
        // Ten books over three pages of four; the two equal titles stay two records.
        assertThat(listing.rows).hasSize(BOOKS)
        val sameTitle = listing.rows.filter { it.title == "Same Title" }
        assertThat(sameTitle.map { it.itemId }.toSet()).hasSize(2)
        // A18b: newest first by default from the server's own dates, and Title keeps its order.
        assertThat(listing.rows.mapNotNull { it.addedAt }).hasSize(BOOKS)
        assertThat(listing.rows.mapNotNull { it.addedAt }).isInOrder(reverseOrder<Long>())
        val byTitle = CatalogRefresher(db, { 0 }).observeListing(profileId(), audiobooks().libraryId, "", CatalogSort.TITLE).first()
        assertThat(byTitle.rows.map { it.title }).isInOrder(String.CASE_INSENSITIVE_ORDER)

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
        assertThat(CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows).hasSize(BOOKS)

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
        assertThat(CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows).hasSize(BOOKS)
    }

    // --- Phase 5: an address whose certificate comes from an installed authority (A26c) ----------

    @Test
    fun phase5InstalledAuthority() = runBlocking {
        assumeTrue(db.profiles().current() != null)
        // The host installs the fixture authority in the emulator's user store and says so.
        assumeTrue("needs -e privateAuthority installed", args.getString("privateAuthority") == "installed")
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        val service = service()

        // Accepted without a prompt: no fingerprint to confirm, and normal validation passed.
        val office = service.checked(OFFICE, "Office")
        assertThat(office.trustedSha256).isNull()
        assertThat(office.passedNormalValidation).isTrue()
        val pairing = service.signIn(office, listener, password)
        assertThat(pairing).isInstanceOf(SignInResult.ConfirmPairing::class.java)
        val officeId = service.confirmPairing((pairing as SignInResult.ConfirmPairing).pairing)

        // With the other addresses disabled it serves the catalog by itself.
        val others = db.addresses().forProfile(profileId()).filter { it.addressId != officeId }
        others.forEach { service.setEnabled(it.addressId, false) }
        assertThat(service.refreshCatalog(audiobooks().libraryId)).isInstanceOf(RefreshOutcome.Published::class.java)
        assertThat(routedTo()).isEqualTo(officeId)
        assertThat(db.addresses().get(officeId)!!.confirmedCertSha256).isNull()

        // Leave the two addresses of the earlier phases as they were.
        others.forEach { service.setEnabled(it.addressId, true) }
        service.remove(officeId)
    }

    // --- Phase 6: the listener manages the addresses (A19) ----------------------------------------

    @Test
    fun phase6ManageAddresses() = runBlocking {
        assumeTrue(db.profiles().current() != null)
        val listener = arg("absListener")
        val password = arg("absListenerPassword")
        val tlsSha256 = arg("tlsSha256")
        val service = service()
        val library = audiobooks().libraryId
        val boundary = CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows.single { it.title == "Boundary Book" }
        val key = BookKey(profileId(), boundary.itemId)
        val files = Fixtures.install("boundary-book")
        LocalEditions.publish(container, key, boundary.title, boundary.authors, files.map { LocalEditions.Track(it, TestApp.probeDuration(it), it.nameWithoutExtension) })
        val homeId = address("Home").addressId
        val tailscaleId = address("Tailscale").addressId
        assertThat(routedTo()).isEqualTo(homeId)

        // Rename: the list shows the new label, and a blank label keeps the old one.
        service.rename(tailscaleId, "Remote")
        service.rename(tailscaleId, "   ")
        assertThat(db.addresses().get(tailscaleId)!!.label).isEqualTo("Remote")

        // Reorder: moving Remote first makes it the active route.
        service.move(tailscaleId, up = true)
        assertThat(db.addresses().forProfile(profileId()).sortedBy { it.priority }.map { it.label }).containsExactly("Remote", "Home").inOrder()
        assertThat(routedTo()).isEqualTo(tailscaleId)

        // Test: each address answers by itself.
        assertThat(service.test(homeId)).isEqualTo(Outcome.Ok(Unit))
        assertThat(service.test(tailscaleId)).isEqualTo(Outcome.Ok(Unit))

        // Removing the active address revokes its session and moves the route to Home.
        service.remove(tailscaleId)
        assertThat(db.addresses().forProfile(profileId()).map { it.label }).containsExactly("Home")
        assertThat(container.tokenVault.read(tailscaleId)).isNull()
        assertThat(routedTo()).isEqualTo(homeId)

        // Add a second address again: the same account pairs after one confirmation.
        val again = service.checked(TAILSCALE, "Tailscale", trust = tlsSha256)
        val pairing = service.signIn(again, listener, password)
        assertThat(pairing).isInstanceOf(SignInResult.ConfirmPairing::class.java)
        val addedId = service.confirmPairing((pairing as SignInResult.ConfirmPairing).pairing)
        assertThat(db.addresses().forProfile(profileId()).sortedBy { it.priority }.map { it.label }).containsExactly("Home", "Tailscale").inOrder()

        // Removing every address, the last one included, keeps the profile, the catalog, the
        // downloaded edition and the saved place.
        service.remove(homeId)
        service.remove(addedId)
        assertThat(db.profiles().current()).isNotNull()
        assertThat(db.addresses().forProfile(profileId())).isEmpty()
        assertThat(CatalogRefresher(db, { 0 }).observeListing(profileId(), library, "").first().rows).hasSize(BOOKS)
        assertThat(container.editionStore.observeEverything().first().map { it.key }).contains(key)
        assertThat(container.progressStore.read(key)!!.positionMs).isEqualTo(115_000)
    }

    private suspend fun routedTo(): String {
        val routed = container.serverAccess.route { it.client.status() }
        assertWithMessage("routed: $routed").that(routed).isInstanceOf(Routed.Done::class.java)
        return (routed as Routed.Done).addressId
    }

    private companion object {
        /** The books of the seeded Audiobooks library. */
        const val BOOKS = 10
        const val PAGE_SIZE = 4
        const val HOME = "http://10.0.2.2:13378/"
        const val TAILSCALE = "https://10.0.2.2:13443/"

        /** The emulator's other name for the host loopback: same TLS front, another origin. */
        const val TAILSCALE_OTHER_ORIGIN = "https://10.0.2.2:13444/"

        /** The TLS front with a certificate for 10.0.2.2 from the installed fixture authority. */
        const val OFFICE = "https://10.0.2.2:13445/"
    }
}
