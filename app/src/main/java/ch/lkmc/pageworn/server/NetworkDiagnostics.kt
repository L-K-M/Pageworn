package ch.lkmc.pageworn.server

enum class DiagnosticOperation {
    REQUEST, STATUS, SIGN_IN, SIGN_OUT, TOKEN_REFRESH, ACCOUNT, LIBRARIES, CATALOG, ITEM_DETAILS, DOWNLOAD, COVER, ROUTE,
}

/**
 * Outcome of one network operation, as a fixed category. Categories carry no server message,
 * certificate fingerprint, address host or request detail.
 */
enum class DiagnosticStatus {
    OK,
    UNREACHABLE,
    BLOCKED,
    CERTIFICATE,
    UNAUTHORIZED,
    SESSION_EXPIRED,
    LOCAL_NETWORK_DENIED,
    SERVER_TOO_OLD,
    NO_PASSWORD_SIGN_IN,
    INVALID_CREDENTIALS,
    RATE_LIMITED,
    FORBIDDEN,
    NOT_FOUND,
    UNEXPECTED,
}

/**
 * One redacted network event. These are the only fields the diagnostic log keeps: the address by
 * its label, an application-defined operation, the outcome category, the duration and the time.
 */
data class NetworkDiagnostic(
    val addressLabel: String,
    val operation: DiagnosticOperation,
    val status: DiagnosticStatus,
    val durationMs: Long,
    val atMillis: Long,
)

/**
 * Bounded, thread-safe in-memory log of [NetworkDiagnostic] entries behind Settings' export.
 *
 * Each operation and outcome is a fixed category. A listener-supplied alias is the only free
 * text, and labels that contain the address host or a URL separator are replaced.
 */
class NetworkDiagnostics(
    private val limit: Int = DEFAULT_LIMIT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    private val lock = Any()
    private val stored = ArrayDeque<NetworkDiagnostic>()

    /** The current reading, for a caller that starts work before recording its outcome. */
    fun nowMillis(): Long = nowMs()

    /**
     * Records the outcome of work that started at [startedAtMillis]. The entry is stamped with
     * the current time and the elapsed duration, which is never negative.
     */
    fun record(
        addressLabel: String,
        operation: DiagnosticOperation,
        status: DiagnosticStatus,
        startedAtMillis: Long = nowMs(),
        host: String? = null,
    ) {
        val at = nowMs()
        val entry = NetworkDiagnostic(
            redactLabel(addressLabel, host),
            operation,
            status,
            (at - startedAtMillis).coerceAtLeast(0),
            at,
        )
        synchronized(lock) {
            if (stored.size >= limit) stored.removeFirst()
            stored.addLast(entry)
        }
    }

    /** The entries in chronological order, oldest first. */
    fun entries(): List<NetworkDiagnostic> = synchronized(lock) { stored.toList() }

    /** The log as plain text rows, oldest first, one per line. */
    fun render(): String = entries().joinToString("\n") { entry ->
        val operation = entry.operation.name.lowercase().replace('_', '-')
        val status = entry.status.name.lowercase().replace('_', '-')
        "${entry.atMillis} ${entry.addressLabel} $operation $status ${entry.durationMs}ms"
    }

    companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_TEXT_CHARS = 80
        const val LABEL_FALLBACK = "(unlabeled)"

        /** The category for one HTTP status, when a request reached the server. */
        fun statusOf(statusCode: Int): DiagnosticStatus = when (statusCode) {
            in 200..299 -> DiagnosticStatus.OK
            401 -> DiagnosticStatus.UNAUTHORIZED
            403 -> DiagnosticStatus.FORBIDDEN
            404 -> DiagnosticStatus.NOT_FOUND
            429 -> DiagnosticStatus.RATE_LIMITED
            else -> DiagnosticStatus.UNEXPECTED
        }

        /** The category for one transport error. */
        fun statusOf(error: ServerError): DiagnosticStatus = when (error) {
            is ServerError.Unreachable -> DiagnosticStatus.UNREACHABLE
            is ServerError.Certificate -> DiagnosticStatus.CERTIFICATE
            ServerError.LocalNetworkDenied -> DiagnosticStatus.LOCAL_NETWORK_DENIED
            is ServerError.ServerTooOld -> DiagnosticStatus.SERVER_TOO_OLD
            ServerError.NoPasswordSignIn -> DiagnosticStatus.NO_PASSWORD_SIGN_IN
            ServerError.InvalidCredentials -> DiagnosticStatus.INVALID_CREDENTIALS
            ServerError.SessionExpired -> DiagnosticStatus.SESSION_EXPIRED
            is ServerError.RateLimited -> DiagnosticStatus.RATE_LIMITED
            ServerError.Forbidden -> DiagnosticStatus.FORBIDDEN
            ServerError.NotFound -> DiagnosticStatus.NOT_FOUND
            is ServerError.Blocked -> DiagnosticStatus.BLOCKED
            is ServerError.Unexpected -> DiagnosticStatus.UNEXPECTED
        }

        /** The category for a routing decision that found no usable address. */
        fun statusOf(reason: NoRoute): DiagnosticStatus = when (reason) {
            is NoRoute.CertificateChanged, is NoRoute.CertificateUnrecognized -> DiagnosticStatus.CERTIFICATE
            is NoRoute.LocalNetworkDenied -> DiagnosticStatus.LOCAL_NETWORK_DENIED
            NoRoute.SignedOut -> DiagnosticStatus.SESSION_EXPIRED
            is NoRoute.Unreachable -> DiagnosticStatus.UNREACHABLE
        }

        private fun redactLabel(value: String, host: String? = null): String {
            val label = value.trim()
            if (label.isEmpty() || host != null && label.contains(host, ignoreCase = true)) return LABEL_FALLBACK
            // URL and filename separators invalidate the whole alias. Removing separators would
            // still expose the host or token they surrounded.
            if (label.any { !it.isLetterOrDigit() && it !in " -_'()" }) return LABEL_FALLBACK
            return label.take(MAX_TEXT_CHARS)
        }
    }
}
