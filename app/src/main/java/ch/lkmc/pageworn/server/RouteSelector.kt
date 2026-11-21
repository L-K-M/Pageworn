package ch.lkmc.pageworn.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the profile picks an address: the first usable one in priority order, or one the listener pinned. */
enum class RouteMode { AUTOMATIC, MANUAL }

/** What the selector needs to know about one address, in the listener's priority order. */
data class RouteCandidate(
    val addressId: String,
    val label: String,
    val priority: Int,
    val enabled: Boolean,
    val paired: Boolean,
    /** A certificate the listener has not confirmed blocks the address until they trust it. */
    val certificateBlock: ServerError.Certificate?,
    val hasCredentials: Boolean,
)

/** Why no address can serve a request. The first blocked address in priority order decides the copy. */
sealed interface NoRoute {
    data class CertificateChanged(val addressId: String, val label: String) : NoRoute
    data class CertificateUnrecognized(val addressId: String, val label: String) : NoRoute
    data class LocalNetworkDenied(val addressId: String, val label: String) : NoRoute

    /** An address answers, but none has a session: the listener must sign in. */
    data object SignedOut : NoRoute

    /** No enabled, paired address answered; in manual mode [label] names the pinned one. */
    data class Unreachable(val label: String?) : NoRoute
}

sealed interface RouteDecision {
    data class Use(val addressId: String) : RouteDecision
    data class None(val reason: NoRoute) : RouteDecision
}

/**
 * Chooses the address for network work. Probing happens only when work is requested: each
 * candidate in priority order gets one short status probe, and the first that answers is reused
 * for the same network until the network changes or a request through it fails to connect.
 * Addresses blocked by a certificate or by a denied local-network permission are skipped without
 * being contacted, so they never look like timeouts.
 */
class RouteSelector(
    private val candidates: suspend () -> List<RouteCandidate>,
    private val localNetworkBlocked: suspend (addressId: String) -> Boolean,
    private val probe: suspend (addressId: String) -> ServerResult<Unit>,
) {
    private val mutex = Mutex()
    private var cachedNetwork: Any? = null
    private var cachedAddress: String? = null

    /**
     * Picks an address on [network] (any value identifying the current default network). In
     * [RouteMode.MANUAL] only [pinnedAddressId] is considered, and an error names it.
     */
    suspend fun select(network: Any?, mode: RouteMode = RouteMode.AUTOMATIC, pinnedAddressId: String? = null): RouteDecision = mutex.withLock {
        if (network != cachedNetwork) {
            cachedNetwork = network
            cachedAddress = null
        }
        val all = candidates().filter { it.enabled && it.paired }.sortedBy { it.priority }
        val pool = if (mode == RouteMode.MANUAL) all.filter { it.addressId == pinnedAddressId } else all
        cachedAddress?.let { cached -> if (pool.any { it.addressId == cached && usableWithoutProbe(it) }) return RouteDecision.Use(cached) }

        var firstBlock: NoRoute? = null
        var answeredWithoutSession = false
        for (candidate in pool) {
            val block = blockOf(candidate)
            if (block != null) {
                if (firstBlock == null) firstBlock = block
                continue
            }
            val answered = when (val result = probe(candidate.addressId)) {
                is ServerResult.Ok -> true
                is ServerResult.Failed -> {
                    // A certificate problem found by the probe blocks the address from now on.
                    val cert = result.error as? ServerError.Certificate
                    if (cert != null && firstBlock == null) firstBlock = certificateNoRoute(candidate, cert)
                    if (result.error == ServerError.LocalNetworkDenied && firstBlock == null) {
                        firstBlock = NoRoute.LocalNetworkDenied(candidate.addressId, candidate.label)
                    }
                    false
                }
            }
            if (!answered) continue
            if (!candidate.hasCredentials) {
                answeredWithoutSession = true
                continue
            }
            cachedAddress = candidate.addressId
            return RouteDecision.Use(candidate.addressId)
        }
        val reason = firstBlock
            ?: if (answeredWithoutSession) NoRoute.SignedOut
            else NoRoute.Unreachable(if (mode == RouteMode.MANUAL) pool.firstOrNull()?.label else null)
        RouteDecision.None(reason)
    }

    /** Forgets the working address, after a request through it could not connect. */
    suspend fun invalidate(addressId: String) = mutex.withLock {
        if (cachedAddress == addressId) cachedAddress = null
    }

    private suspend fun usableWithoutProbe(candidate: RouteCandidate): Boolean =
        candidate.hasCredentials && blockOf(candidate) == null

    private suspend fun blockOf(candidate: RouteCandidate): NoRoute? {
        candidate.certificateBlock?.let { return certificateNoRoute(candidate, it) }
        if (localNetworkBlocked(candidate.addressId)) return NoRoute.LocalNetworkDenied(candidate.addressId, candidate.label)
        return null
    }

    private fun certificateNoRoute(candidate: RouteCandidate, error: ServerError.Certificate): NoRoute = when (error.kind) {
        ServerError.Certificate.Kind.CHANGED -> NoRoute.CertificateChanged(candidate.addressId, candidate.label)
        ServerError.Certificate.Kind.UNRECOGNIZED -> NoRoute.CertificateUnrecognized(candidate.addressId, candidate.label)
    }
}
