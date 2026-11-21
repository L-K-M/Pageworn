package ch.lkmc.pageworn.account

import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.CertificateState
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.PairingState
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.server.AbsClient
import ch.lkmc.pageworn.server.AddressHttp
import ch.lkmc.pageworn.server.AddressPolicy
import ch.lkmc.pageworn.server.AddressSession
import ch.lkmc.pageworn.server.BaseUrl
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.server.DiagnosticOperation
import ch.lkmc.pageworn.server.NetworkDiagnostics
import ch.lkmc.pageworn.server.NoRoute
import ch.lkmc.pageworn.server.RouteCandidate
import ch.lkmc.pageworn.server.RouteDecision
import ch.lkmc.pageworn.server.RouteMode
import ch.lkmc.pageworn.server.RouteSelector
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import ch.lkmc.pageworn.server.TokenVault
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** The client objects for one stored address, rebuilt whenever its transport policy changes. */
class AddressConnection(val address: AddressEntity, val http: AddressHttp, val client: AbsClient, val session: AddressSession)

/** The result of work routed to some paired address. */
sealed interface Routed<out T> {
    data class Done<T>(val value: T, val addressId: String) : Routed<T>

    /** The chosen address answered with an error that another address would not change (403, 404, a bad body). */
    data class Failed(val error: ServerError, val addressId: String) : Routed<Nothing>

    /** No address could serve the request. */
    data class Unavailable(val reason: NoRoute) : Routed<Nothing>
}

/**
 * Owns the per-address clients and routes network work for the profile. It records what each
 * request taught about its address: a certificate problem blocks the address, a session that
 * cannot be renewed marks it expired, a normally validated answer is remembered so that a later
 * validation failure reads as a changed certificate.
 */
class ServerAccess(
    private val db: PagewornDatabase,
    private val vault: TokenVault,
    private val localNetwork: LocalNetworkAccess,
    private val sharedHttp: OkHttpClient,
    private val platformTrust: X509TrustManager,
    private val nowMs: () -> Long,
    private val currentNetwork: () -> Any?,
    private val routeMode: () -> Pair<RouteMode, String?>,
    private val diagnostics: NetworkDiagnostics? = null,
) {
    private val connections = ConcurrentHashMap<String, AddressConnection>()

    private val selector = RouteSelector(
        candidates = ::candidates,
        localNetworkBlocked = { id -> db.addresses().get(id)?.let { localNetwork.blocks(BaseUrl(it.baseUrl).resolve("").host) } ?: false },
        probe = { id ->
            val address = db.addresses().get(id) ?: return@RouteSelector ServerResult.Failed(ServerError.Unreachable("removed"))
            val connection = connection(address)
            when (val status = connection.client.status()) {
                is ServerResult.Ok -> ServerResult.Ok(Unit).also { recordSuccess(connection) }
                is ServerResult.Failed -> status.also { record(address, it.error) }
            }
        },
    )

    /** The client objects for [address]; reused while its policy is unchanged. */
    fun connection(address: AddressEntity): AddressConnection {
        val policy = policyOf(address)
        connections[address.addressId]?.let { if (it.http.policy == policy && it.address.label == address.label) return it }
        val http = AddressHttp(
            sharedHttp, policy, platformTrust,
            diagnostics = diagnostics, addressLabel = address.label,
        )
        val client = AbsClient(http)
        val connection = AddressConnection(address, http, client, AddressSession(address.addressId, client, vault, nowMs))
        connections[address.addressId] = connection
        return connection
    }

    /** A connection for an address that is not stored yet (connection setup and pairing). */
    fun transient(policy: AddressPolicy, addressId: String, label: String = ""): AddressConnection {
        val http = AddressHttp(sharedHttp, policy, platformTrust, diagnostics = diagnostics, addressLabel = label)
        val client = AbsClient(http)
        val placeholder = AddressEntity(addressId = addressId, profileId = "", baseUrl = policy.baseUrl.value, label = label, priority = 0)
        return AddressConnection(placeholder, http, client, AddressSession(addressId, client, vault, nowMs))
    }

    fun forget(addressId: String) {
        connections.remove(addressId)
    }

    /**
     * Runs [work] through the chosen address. A connection failure or a certificate problem moves
     * on to the next usable address, which is safe for the reads and transfers routed here; an
     * expired session marks its address and does the same. Other errors end the request.
     */
    suspend fun <T> route(work: suspend (AddressConnection) -> ServerResult<T>): Routed<T> {
        val (mode, pinned) = routeMode()
        val tried = mutableSetOf<String>()
        val startedAtMillis = diagnostics?.nowMillis() ?: 0L
        while (true) {
            val decision = selector.select(currentNetwork(), mode, pinned)
            val addressId = when (decision) {
                is RouteDecision.None -> {
                    diagnostics?.let { log ->
                        val label = routeLabel(decision.reason)
                        val address = db.profiles().current()?.let { db.addresses().forProfile(it.profileId) }
                            ?.singleOrNull { it.label == label }
                        log.record(address?.label.orEmpty(), DiagnosticOperation.ROUTE, NetworkDiagnostics.statusOf(decision.reason),
                            startedAtMillis, address?.let { BaseUrl(it.baseUrl).resolve("").host })
                    }
                    return Routed.Unavailable(decision.reason)
                }
                is RouteDecision.Use -> decision.addressId
            }
            // Each address gets one attempt per request, so a flapping address cannot loop.
            if (!tried.add(addressId)) return Routed.Unavailable(NoRoute.Unreachable(null))
            val address = db.addresses().get(addressId) ?: continue
            val connection = connection(address)
            when (val result = work(connection)) {
                is ServerResult.Ok -> {
                    recordSuccess(connection)
                    return Routed.Done(result.value, addressId)
                }
                is ServerResult.Failed -> {
                    record(address, result.error)
                    if (!movesOn(result.error)) return Routed.Failed(result.error, addressId)
                    // The attempt through this address already reached the log; this entry records
                    // that routing abandoned it and moved on.
                    diagnostics?.record(address.label, DiagnosticOperation.ROUTE, NetworkDiagnostics.statusOf(result.error),
                        startedAtMillis, connection.http.policy.baseUrl.resolve("").host)
                    selector.invalidate(addressId)
                }
            }
        }
    }

    /** Forgets the cached route, after an address was added, changed or removed. */
    suspend fun invalidateRoutes() {
        db.profiles().current()?.let { profile -> db.addresses().forProfile(profile.profileId).forEach { selector.invalidate(it.addressId) } }
    }

    /** The label a routing failure names, when it names one address. */
    private fun routeLabel(reason: NoRoute): String? = when (reason) {
        is NoRoute.CertificateChanged -> reason.label
        is NoRoute.CertificateUnrecognized -> reason.label
        is NoRoute.LocalNetworkDenied -> reason.label
        is NoRoute.Unreachable -> reason.label
        NoRoute.SignedOut -> null
    }

    private fun movesOn(error: ServerError): Boolean = when (error) {
        is ServerError.Unreachable, is ServerError.Certificate, ServerError.LocalNetworkDenied, ServerError.SessionExpired -> true
        else -> false
    }

    private suspend fun candidates(): List<RouteCandidate> {
        val profile = db.profiles().current() ?: return emptyList()
        return db.addresses().forProfile(profile.profileId).map { a ->
            RouteCandidate(
                addressId = a.addressId,
                label = a.label,
                priority = a.priority,
                enabled = a.enabled,
                paired = a.pairingState == PairingState.PAIRED,
                certificateBlock = when (a.certificateState) {
                    CertificateState.OK -> null
                    CertificateState.UNRECOGNIZED -> ServerError.Certificate(a.presentedCertSha256.orEmpty(), ServerError.Certificate.Kind.UNRECOGNIZED)
                    CertificateState.CHANGED -> ServerError.Certificate(a.presentedCertSha256.orEmpty(), ServerError.Certificate.Kind.CHANGED)
                },
                hasCredentials = a.sessionState == SessionState.SIGNED_IN && vault.read(a.addressId) != null,
            )
        }
    }

    /** Remembers a successful answer: the time, and whether it passed normal certificate validation. */
    suspend fun recordSuccess(connection: AddressConnection) {
        val current = db.addresses().get(connection.address.addressId) ?: return
        val validated = current.passedNormalValidation || connection.http.passedNormalValidation
        db.addresses().upsert(current.copy(lastSuccessAt = nowMs(), passedNormalValidation = validated))
    }

    /** Remembers what an error says about its address: a certificate problem or an expired session. */
    suspend fun record(address: AddressEntity, error: ServerError) {
        val current = db.addresses().get(address.addressId) ?: return
        val updated = when (error) {
            is ServerError.Certificate -> current.copy(
                certificateState = if (error.kind == ServerError.Certificate.Kind.CHANGED) CertificateState.CHANGED else CertificateState.UNRECOGNIZED,
                presentedCertSha256 = error.presentedSha256,
            )
            ServerError.SessionExpired -> current.copy(sessionState = SessionState.EXPIRED)
            else -> return
        }
        db.addresses().upsert(updated)
    }

    companion object {

        fun policyOf(address: AddressEntity) = AddressPolicy(
            baseUrl = BaseUrl(address.baseUrl),
            cleartextOptIn = address.cleartextOptIn,
            confirmedCertSha256 = address.confirmedCertSha256,
            passedNormalValidation = address.passedNormalValidation,
        )
    }
}
