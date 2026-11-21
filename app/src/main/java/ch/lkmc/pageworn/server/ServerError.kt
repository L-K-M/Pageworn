package ch.lkmc.pageworn.server

/**
 * Why a request to one address did not produce a usable answer. Each case maps to one message
 * of the product brief; callers never collapse them into a generic failure.
 */
sealed interface ServerError {
    /** Timeout, refused connection, DNS failure or no network: failover may try another address. */
    data class Unreachable(val detail: String) : ServerError

    /** The address presented a certificate outside the trust anchors, or not the confirmed one. Nothing was sent. */
    data class Certificate(val presentedSha256: String, val kind: Kind) : ServerError {
        enum class Kind {
            /** No certificate was confirmed for this origin and it never passed normal validation. */
            UNRECOGNIZED,

            /** The confirmed certificate was replaced, or an address that used to validate no longer does. */
            CHANGED,
        }
    }

    /** Android 17 blocks this local-network address until the listener grants the permission. */
    data object LocalNetworkDenied : ServerError

    /** The server is older than 2.26.0, the first release with the access and refresh token flow. */
    data class ServerTooOld(val version: String) : ServerError

    /** `authMethods` does not include `local`. */
    data object NoPasswordSignIn : ServerError

    /** Wrong username or password at sign-in. */
    data object InvalidCredentials : ServerError

    /** The session cannot be renewed: the address needs a new sign-in. */
    data object SessionExpired : ServerError

    /** A 403 on a download route: a permission decision, final for this request. */
    data object Forbidden : ServerError

    /** 404 for an item or file. */
    data object NotFound : ServerError

    /** The request would leave the approved origin and path, or downgrade to HTTP. Nothing was sent there. */
    data class Blocked(val reason: String) : ServerError

    /** Any other status, or a body that does not match the pinned contract. */
    data class Unexpected(val status: Int?, val detail: String) : ServerError
}

/** A result that is either a value or a [ServerError]. */
sealed interface ServerResult<out T> {
    data class Ok<T>(val value: T) : ServerResult<T>
    data class Failed(val error: ServerError) : ServerResult<Nothing>
}
