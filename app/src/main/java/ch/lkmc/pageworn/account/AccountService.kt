package ch.lkmc.pageworn.account

import androidx.room.withTransaction
import ch.lkmc.pageworn.catalog.CatalogPage
import ch.lkmc.pageworn.catalog.CatalogRefresher
import ch.lkmc.pageworn.catalog.CatalogSource
import ch.lkmc.pageworn.catalog.RefreshOutcome
import ch.lkmc.pageworn.catalog.RemoteLibrary
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.PairingState
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.server.Account
import ch.lkmc.pageworn.server.AddressPolicy
import ch.lkmc.pageworn.server.BaseUrl
import ch.lkmc.pageworn.server.BaseUrlParse
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import ch.lkmc.pageworn.server.TokenVault
import ch.lkmc.pageworn.server.parseBaseUrl
import ch.lkmc.pageworn.server.signInBlocker
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A value, or the problem that stopped it. */
sealed interface Outcome<out T, out E> {
    data class Ok<T>(val value: T) : Outcome<T, Nothing>
    data class Err<E>(val problem: E) : Outcome<Nothing, E>
}

/** An address the listener entered and the server behind it answered; not stored yet. */
data class CheckedAddress(val baseUrl: BaseUrl, val label: String, val cleartextOptIn: Boolean, val trustedSha256: String?, val passedNormalValidation: Boolean)

/** Why the address step of connection setup stopped, each shown under the address field. */
sealed interface AddressProblem {
    data class InvalidUrl(val reason: BaseUrlParse.Reason) : AddressProblem

    /** An http address: the listener must accept the cleartext tradeoff explicitly. */
    data class CleartextChoice(val baseUrl: BaseUrl) : AddressProblem

    /** Android 17 needs the local-network permission before the first contact with [host]. */
    data class LocalNetworkPermission(val host: String, val denied: Boolean) : AddressProblem
    data class Certificate(val sha256: String, val kind: ServerError.Certificate.Kind) : AddressProblem
    data object Unreachable : AddressProblem
    data object ServerTooOld : AddressProblem
    data object NoPasswordSignIn : AddressProblem
}

sealed interface SignInResult {
    data class Connected(val profileId: String) : SignInResult
    data object InvalidCredentials : SignInResult

    /** The server refuses more sign-ins from this client for now; the listener waits and tries again. */
    data object TooManyAttempts : SignInResult
    data class AddressFailed(val problem: AddressProblem) : SignInResult

    /** The server answered for another account than the one whose books this phone holds. */
    data class DifferentAccount(val accountName: String) : SignInResult

    /** Same account, but the server lacks the library seen at first sign-in: another server. */
    data object PairingMismatch : SignInResult

    /** A new alias matched; the listener confirms it once with [confirmPairing]. */
    data class ConfirmPairing(val pairing: PendingPairing, val username: String, val libraryName: String?) : SignInResult
}

/** A matched alias waiting for the listener's confirmation; holds its fresh session in memory only. */
class PendingPairing internal constructor(internal val address: CheckedAddress, internal val account: Account)

/** What Library and a Download tap show when the server cannot be used. */
sealed interface ServerNotice {
    data object Unreachable : ServerNotice
    data object SignedOut : ServerNotice
    data class CertificateChanged(val label: String) : ServerNotice
    data class CertificateUnrecognized(val label: String) : ServerNotice
    data class LocalNetworkDenied(val label: String) : ServerNotice
}

/**
 * Sign-in, pairing, sign-out, the address list, the libraries and the catalog: the application
 * service the connection, settings and Library screens use. It never deletes editions or
 * progress; signing out or removing every address keeps the profile and its books.
 */
class AccountService(
    private val db: PagewornDatabase,
    private val vault: TokenVault,
    private val access: ServerAccess,
    private val localNetwork: LocalNetworkAccess,
    private val refresher: CatalogRefresher,
    private val nowMs: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _notice = MutableStateFlow<ServerNotice?>(null)

    /** The last server problem of a Library-facing operation; cleared by the next success. */
    val notice: StateFlow<ServerNotice?> = _notice.asStateFlow()

    // --- Connection setup ---------------------------------------------------------------------

    /**
     * Checks the address step: the URL, the cleartext choice, the local-network permission, the
     * certificate and the server's version and sign-in methods. Nothing is stored.
     * [trustedSha256] is a fingerprint the listener chose to trust on this screen.
     */
    suspend fun checkAddress(input: String, label: String, allowCleartext: Boolean, trustedSha256: String?): Outcome<CheckedAddress, AddressProblem> {
        val baseUrl = when (val parsed = parseBaseUrl(input, allowCleartext)) {
            is BaseUrlParse.Invalid -> return Outcome.Err(AddressProblem.InvalidUrl(parsed.reason))
            is BaseUrlParse.NeedsCleartextChoice -> return Outcome.Err(AddressProblem.CleartextChoice(parsed.baseUrl))
            is BaseUrlParse.Valid -> parsed.baseUrl
        }
        val host = baseUrl.resolve("").host
        if (localNetwork.blocks(host)) return Outcome.Err(AddressProblem.LocalNetworkPermission(host, denied = false))
        val policy = AddressPolicy(baseUrl, cleartextOptIn = baseUrl.isCleartext, confirmedCertSha256 = trustedSha256, passedNormalValidation = false)
        val connection = access.transient(policy, newId(), label)
        val status = when (val result = connection.client.status()) {
            is ServerResult.Failed -> return Outcome.Err(addressProblem(result.error))
            is ServerResult.Ok -> result.value
        }
        signInBlocker(status)?.let { return Outcome.Err(addressProblem(it)) }
        val name = label.trim().ifEmpty { host }
        return Outcome.Ok(CheckedAddress(baseUrl, name, baseUrl.isCleartext, trustedSha256, connection.http.passedNormalValidation))
    }

    /**
     * Signs in on a checked address. On a clean install this creates the profile. On a phone that
     * holds a profile, the account must be the same: signing in again on a known address restores
     * its session, and a new address becomes an alias after [confirmPairing].
     */
    suspend fun signIn(address: CheckedAddress, username: String, password: String): SignInResult {
        val policy = AddressPolicy(address.baseUrl, address.cleartextOptIn, address.trustedSha256, address.passedNormalValidation)
        val connection = access.transient(policy, newId(), address.label)
        val account = when (val result = connection.client.login(username, password)) {
            is ServerResult.Failed -> return signInFailure(result.error)
            is ServerResult.Ok -> result.value
        }
        val profile = db.profiles().current()
        if (profile != null) {
            if (account.accountId != profile.accountId) {
                connection.client.logout(account.tokens.refreshToken)
                return SignInResult.DifferentAccount(profile.accountName)
            }
            val existing = db.addresses().forProfile(profile.profileId).firstOrNull { it.baseUrl == address.baseUrl.value }
            if (existing != null) {
                restoreSession(existing, address, account)
                return SignInResult.Connected(profile.profileId)
            }
        }

        // First sign-in and new aliases need library evidence from a successful request.
        val libraries = when (val result = connection.client.libraries(account.tokens.accessToken)) {
            is ServerResult.Ok -> result.value
            is ServerResult.Failed -> {
                connection.client.logout(account.tokens.refreshToken)
                return SignInResult.AddressFailed(addressProblem(result.error))
            }
        }
        if (profile == null) return createProfile(address, account, libraries)
        val evidence = profile.pairingLibraryId
        val library = libraries.firstOrNull { it.libraryId == evidence }
        if (evidence != null && library == null) {
            connection.client.logout(account.tokens.refreshToken)
            return SignInResult.PairingMismatch
        }
        return SignInResult.ConfirmPairing(PendingPairing(address, account), account.username, library?.name)
    }

    /** Stores a confirmed alias with its session, after the listener saw what matched. */
    suspend fun confirmPairing(pairing: PendingPairing): String {
        val profile = checkNotNull(db.profiles().current()) { "Pairing needs a profile" }
        val addressId = newId()
        val priority = (db.addresses().forProfile(profile.profileId).maxOfOrNull { it.priority } ?: -1) + 1
        vault.write(addressId, pairing.account.tokens)
        db.addresses().upsert(newAddress(addressId, profile.profileId, pairing.address, priority))
        access.invalidateRoutes()
        return addressId
    }

    /** Discards a pairing the listener declined, revoking its fresh session. */
    suspend fun declinePairing(pairing: PendingPairing) {
        val policy = AddressPolicy(pairing.address.baseUrl, pairing.address.cleartextOptIn, pairing.address.trustedSha256, pairing.address.passedNormalValidation)
        access.transient(policy, newId(), pairing.address.label).client.logout(pairing.account.tokens.refreshToken)
    }

    private suspend fun createProfile(address: CheckedAddress, account: Account, libraries: List<RemoteLibrary>): SignInResult {
        val profileId = newId()
        val addressId = newId()
        vault.write(addressId, account.tokens)
        db.withTransaction {
            db.profiles().upsert(
                ProfileEntity(
                    profileId = profileId,
                    accountId = account.accountId,
                    accountName = account.username,
                    pairingLibraryId = libraries.firstOrNull()?.libraryId,
                    chosenLibraryId = libraries.firstOrNull()?.libraryId,
                    serverVersion = null,
                    createdAt = nowMs(),
                    canDownload = account.canDownload,
                ),
            )
            db.addresses().upsert(newAddress(addressId, profileId, address, priority = 0))
            storeLibraries(profileId, libraries)
        }
        _notice.value = null
        return SignInResult.Connected(profileId)
    }

    private suspend fun restoreSession(existing: AddressEntity, address: CheckedAddress, account: Account) {
        vault.write(existing.addressId, account.tokens)
        db.addresses().upsert(
            existing.copy(
                sessionState = SessionState.SIGNED_IN,
                confirmedCertSha256 = address.trustedSha256 ?: existing.confirmedCertSha256,
                certificateState = CertificateState.OK,
                presentedCertSha256 = null,
                passedNormalValidation = existing.passedNormalValidation || address.passedNormalValidation,
                lastSuccessAt = nowMs(),
            ),
        )
        db.profiles().current()?.let { db.profiles().setCanDownload(it.profileId, account.canDownload) }
        access.forget(existing.addressId)
        access.invalidateRoutes()
        _notice.value = null
    }

    private fun newAddress(addressId: String, profileId: String, address: CheckedAddress, priority: Int) = AddressEntity(
        addressId = addressId,
        profileId = profileId,
        baseUrl = address.baseUrl.value,
        label = address.label,
        priority = priority,
        cleartextOptIn = address.cleartextOptIn,
        confirmedCertSha256 = address.trustedSha256,
        passedNormalValidation = address.passedNormalValidation,
        lastSuccessAt = nowMs(),
        credentialRef = addressId,
    )

    // --- Signing out ----------------------------------------------------------------------------

    /**
     * Revokes the session of every address that answers and deletes every local credential,
     * whether or not a server answered. Keeps the profile, its addresses, the catalog, editions
     * and progress.
     */
    suspend fun signOut() {
        val profile = db.profiles().current() ?: return
        val addresses = db.addresses().forProfile(profile.profileId)
        coroutineScope {
            addresses.map { address -> async { runCatching { access.connection(address).session.signOut() } } }.awaitAll()
        }
        vault.deleteAll()
        db.addresses().upsertAll(addresses.map { it.copy(sessionState = SessionState.SIGNED_OUT) })
        access.invalidateRoutes()
        _notice.value = ServerNotice.SignedOut
    }

    // --- The address list -----------------------------------------------------------------------

    suspend fun rename(addressId: String, label: String) = update(addressId) { it.copy(label = label.trim().ifEmpty { it.label }) }

    suspend fun setEnabled(addressId: String, enabled: Boolean) = update(addressId) { it.copy(enabled = enabled) }

    /** Moves an address one place up or down in the priority order. */
    suspend fun move(addressId: String, up: Boolean) {
        val profile = db.profiles().current() ?: return
        val ordered = db.addresses().forProfile(profile.profileId).sortedBy { it.priority }.toMutableList()
        val index = ordered.indexOfFirst { it.addressId == addressId }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in ordered.indices) return
        ordered.add(target, ordered.removeAt(index))
        db.addresses().upsertAll(ordered.mapIndexed { i, a -> a.copy(priority = i) })
        access.invalidateRoutes()
    }

    /** Trusts the certificate the address presented last; only that origin accepts it. */
    suspend fun trustPresentedCertificate(addressId: String) = update(addressId) {
        val presented = it.presentedCertSha256 ?: return@update it
        it.copy(confirmedCertSha256 = presented, certificateState = CertificateState.OK, presentedCertSha256 = null)
    }

    /**
     * Removes an address: its session is revoked when reachable and its credential deleted. The
     * profile, the other addresses, books and places stay, even when it was the last address.
     */
    suspend fun remove(addressId: String) {
        val address = db.addresses().get(addressId) ?: return
        runCatching { access.connection(address).session.signOut() }
        vault.delete(addressId)
        db.addresses().delete(addressId)
        access.forget(addressId)
        access.invalidateRoutes()
    }

    /** Contacts one address with a status probe, for the Test action. */
    suspend fun test(addressId: String): Outcome<Unit, AddressProblem> {
        val address = db.addresses().get(addressId) ?: return Outcome.Err(AddressProblem.Unreachable)
        val host = BaseUrl(address.baseUrl).resolve("").host
        if (localNetwork.blocks(host)) return Outcome.Err(AddressProblem.LocalNetworkPermission(host, denied = true))
        val connection = access.connection(address)
        return when (val result = connection.client.status()) {
            is ServerResult.Ok -> Outcome.Ok(Unit).also { access.recordSuccess(connection) }
            is ServerResult.Failed -> Outcome.Err(addressProblem(result.error)).also { access.record(address, result.error) }
        }
    }

    private suspend fun update(addressId: String, change: (AddressEntity) -> AddressEntity) {
        val address = db.addresses().get(addressId) ?: return
        db.addresses().upsert(change(address))
        access.forget(addressId)
        access.invalidateRoutes()
    }

    // --- Libraries and catalog ------------------------------------------------------------------

    /** Refreshes the accessible book libraries and the download permission. */
    suspend fun refreshLibraries(): Boolean {
        val profile = db.profiles().current() ?: return false
        val routed = access.route { c ->
            c.session.authorized { token ->
                when (val libraries = c.client.libraries(token)) {
                    is ServerResult.Failed -> libraries
                    is ServerResult.Ok -> when (val me = c.client.me(token)) {
                        is ServerResult.Failed -> me
                        is ServerResult.Ok -> ServerResult.Ok(libraries.value to me.value.canDownload)
                    }
                }
            }
        }
        val (libraries, canDownload) = done(routed) ?: return false
        db.withTransaction {
            storeLibraries(profile.profileId, libraries)
            db.profiles().setCanDownload(profile.profileId, canDownload)
            val chosen = profile.chosenLibraryId?.takeIf { id -> libraries.any { it.libraryId == id } } ?: libraries.firstOrNull()?.libraryId
            db.profiles().setChosenLibrary(profile.profileId, chosen)
        }
        return true
    }

    private val refreshing = Mutex()

    /**
     * Refreshes the libraries, then the catalog of [libraryId] or of the chosen library. Callers
     * run it in the application scope so a screen going away does not cancel it; overlapping
     * calls wait for each other rather than walking the same pages twice.
     */
    suspend fun refreshLibraryAndCatalog(libraryId: String? = null): Boolean = refreshing.withLock {
        if (!refreshLibraries()) return@withLock false
        val selected = libraryId ?: db.profiles().current()?.chosenLibraryId ?: return@withLock true
        refreshCatalog(selected)
        true
    }

    suspend fun chooseLibrary(libraryId: String) {
        val profile = db.profiles().current() ?: return
        db.profiles().setChosenLibrary(profile.profileId, libraryId)
    }

    /** Refreshes one library's catalog through whichever address answers. */
    suspend fun refreshCatalog(libraryId: String): RefreshOutcome? {
        val profile = db.profiles().current() ?: return null
        var unavailable: NoRoute? = null
        val source = object : CatalogSource {
            override suspend fun page(page: Int, limit: Int) = through { c, token -> c.client.itemsPage(token, libraryId, page, limit) }
            override suspend fun all() = through { c, token -> c.client.allItems(token, libraryId) }

            private suspend fun through(call: suspend (AddressConnection, String) -> ServerResult<CatalogPage>): ServerResult<CatalogPage> =
                when (val routed = access.route { c -> c.session.authorized { token -> call(c, token) } }) {
                    is Routed.Done -> ServerResult.Ok(routed.value)
                    is Routed.Failed -> ServerResult.Failed(routed.error)
                    is Routed.Unavailable -> {
                        unavailable = routed.reason
                        ServerResult.Failed(ServerError.Unreachable("no usable address"))
                    }
                }
        }
        val outcome = refresher.refresh(profile.profileId, libraryId, source)
        when (outcome) {
            is RefreshOutcome.Published -> _notice.value = null
            is RefreshOutcome.Failed -> _notice.value = unavailable?.let(::noticeOf) ?: noticeOf(outcome.error)
            RefreshOutcome.Superseded -> Unit
        }
        return outcome
    }

    private suspend fun storeLibraries(profileId: String, libraries: List<RemoteLibrary>) {
        val existing = db.libraries().forProfile(profileId).associateBy { it.libraryId }
        db.libraries().upsertAll(
            libraries.map { l ->
                existing[l.libraryId]?.copy(name = l.name, displayOrder = l.displayOrder)
                    ?: LibraryEntity(profileId, l.libraryId, l.name, l.displayOrder)
            },
        )
        val kept = libraries.map { it.libraryId }
        // A library the account can no longer read leaves the listing; local books stay.
        existing.keys.filter { it !in kept }.forEach { db.catalog().clearLibrary(profileId, it) }
        db.libraries().deleteOthers(profileId, kept)
    }

    /**
     * A Download tap found no usable route or a failing server: Library repeats the state a
     * refresh would show, until the next successful request clears it (A17).
     */
    fun reportUnavailable(reason: NoRoute) {
        _notice.value = noticeOf(reason)
    }

    fun reportFailure(error: ServerError) {
        _notice.value = noticeOf(error)
    }

    private fun <T> done(routed: Routed<T>): T? = when (routed) {
        is Routed.Done -> routed.value.also { _notice.value = null }
        is Routed.Failed -> null.also { _notice.value = noticeOf(routed.error) }
        is Routed.Unavailable -> null.also { _notice.value = noticeOf(routed.reason) }
    }

    private fun noticeOf(reason: NoRoute): ServerNotice = when (reason) {
        is NoRoute.CertificateChanged -> ServerNotice.CertificateChanged(reason.label)
        is NoRoute.CertificateUnrecognized -> ServerNotice.CertificateUnrecognized(reason.label)
        is NoRoute.LocalNetworkDenied -> ServerNotice.LocalNetworkDenied(reason.label)
        NoRoute.SignedOut -> ServerNotice.SignedOut
        is NoRoute.Unreachable -> ServerNotice.Unreachable
    }

    private fun noticeOf(error: ServerError): ServerNotice = when (error) {
        ServerError.SessionExpired -> ServerNotice.SignedOut
        else -> ServerNotice.Unreachable
    }

}

/** What a failed login tells the listener: the credentials, the limit, or the address. */
internal fun signInFailure(error: ServerError): SignInResult = when (error) {
    ServerError.InvalidCredentials -> SignInResult.InvalidCredentials
    is ServerError.RateLimited -> SignInResult.TooManyAttempts
    else -> SignInResult.AddressFailed(addressProblem(error))
}

private fun addressProblem(error: ServerError): AddressProblem = when (error) {
    is ServerError.Certificate -> AddressProblem.Certificate(error.presentedSha256, error.kind)
    is ServerError.ServerTooOld -> AddressProblem.ServerTooOld
    ServerError.NoPasswordSignIn -> AddressProblem.NoPasswordSignIn
    else -> AddressProblem.Unreachable
}
