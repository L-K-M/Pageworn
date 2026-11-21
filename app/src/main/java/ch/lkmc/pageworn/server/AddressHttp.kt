package ch.lkmc.pageworn.server

import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AddressPolicy(
    val baseUrl: BaseUrl,
    val cleartextOptIn: Boolean,
    val confirmedCertSha256: String?,
    val passedNormalValidation: Boolean,
)

class AddressHttp(
    shared: OkHttpClient,
    val policy: AddressPolicy,
    platformTrust: X509TrustManager,
    connectTimeoutMs: Long = 1_500,
    private val diagnostics: NetworkDiagnostics? = null,
    private val addressLabel: String = "",
) {
    private val trust = AddressTrustManager(platformTrust, policy.confirmedCertSha256)
    private val client: OkHttpClient

    @Volatile
    var passedNormalValidation: Boolean = policy.passedNormalValidation
        private set

    init {
        require(connectTimeoutMs > 0)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), null)
        val defaultVerifier = shared.hostnameVerifier
        client = shared.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(connectTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { hostname, session ->
                val pin = policy.confirmedCertSha256
                if (pin == null) {
                    if (defaultVerifier.verify(hostname, session)) true
                    else {
                        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                        if (leaf != null) throw RejectedHostname(sha256Fingerprint(leaf))
                        false
                    }
                } else (session.peerCertificates.firstOrNull() as? X509Certificate)
                    ?.let { sha256Fingerprint(it).equals(pin, ignoreCase = true) } == true
            }
            .addInterceptor { chain ->
                val url = chain.request().url
                if (!policy.baseUrl.contains(url)) throw RefusedRequest("Outside approved address")
                if (url.scheme == "http" && !policy.cleartextOptIn) throw RefusedRequest("Cleartext not approved")
                chain.proceed(chain.request())
            }
            .build()
    }

    /**
     * Runs one approved request and records a redacted entry under [operation]. The entry keeps
     * the outcome category only; the URL, its query, credentials and any error message stay out.
     */
    suspend fun execute(request: Request, operation: DiagnosticOperation = DiagnosticOperation.REQUEST): ServerResult<Response> {
        val startedAtMillis = diagnostics?.nowMillis() ?: 0L
        val result = sendOnce(request)
        val status = when (result) {
            is ServerResult.Ok -> NetworkDiagnostics.statusOf(result.value.code)
            is ServerResult.Failed -> NetworkDiagnostics.statusOf(result.error)
        }
        diagnostics?.record(addressLabel, operation, status, startedAtMillis, policy.baseUrl.resolve("").host)
        return result
    }

    private suspend fun sendOnce(request: Request): ServerResult<Response> = try {
        val response = client.newCall(request).await()
        if (request.url.isHttps && policy.confirmedCertSha256 == null) passedNormalValidation = true
        ServerResult.Ok(response)
    } catch (error: IOException) {
        val failures = allCauses(error)
        val refused = failures.filterIsInstance<RefusedRequest>().firstOrNull()
        if (refused != null) return ServerResult.Failed(ServerError.Blocked(refused.message ?: "Request refused"))

        val rejected = failures.filterIsInstance<RejectedCertificate>().firstOrNull()
        val fingerprint = rejected?.fingerprint ?: failures.filterIsInstance<RejectedHostname>().firstOrNull()?.fingerprint
        if (fingerprint != null) {
            val kind = if (policy.confirmedCertSha256 != null || passedNormalValidation) {
                ServerError.Certificate.Kind.CHANGED
            } else ServerError.Certificate.Kind.UNRECOGNIZED
            return ServerResult.Failed(ServerError.Certificate(fingerprint, kind))
        }
        ServerResult.Failed(ServerError.Unreachable(error.message ?: error.javaClass.simpleName))
    }

    suspend fun executeFollowingRedirects(request: Request, operation: DiagnosticOperation = DiagnosticOperation.REQUEST): ServerResult<Response> {
        var current = request
        repeat(4) { redirects ->
            when (val result = execute(current, operation)) {
                is ServerResult.Failed -> return result
                is ServerResult.Ok -> {
                    val response = result.value
                    if (response.code !in 300..399) return result
                    val location = response.header("Location")
                    val next = location?.let { response.request.url.resolve(it) }
                    response.close()
                    if (next == null || !policy.baseUrl.contains(next) ||
                        (current.url.isHttps && !next.isHttps) || redirects == 3
                    ) return ServerResult.Failed(ServerError.Blocked("Redirect outside approved address or redirect limit"))
                    current = current.newBuilder().url(next).build()
                }
            }
        }
        return ServerResult.Failed(ServerError.Blocked("Redirect limit"))
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response)
                else response.close()
            }
        })
    }


    private fun allCauses(error: Throwable): List<Throwable> {
        val seen = mutableSetOf<Throwable>()
        fun visit(current: Throwable) {
            if (!seen.add(current)) return
            current.cause?.let(::visit)
            current.suppressed.forEach(::visit)
        }
        visit(error)
        return seen.toList()
    }
}

private class RefusedRequest(message: String) : IOException(message)

private class RejectedCertificate(val fingerprint: String, cause: Throwable? = null) :
    CertificateException("Certificate rejected", cause)

private class RejectedHostname(val fingerprint: String) : SSLPeerUnverifiedException("Certificate name rejected")

private class AddressTrustManager(
    private val delegate: X509TrustManager,
    private val pin: String?,
) : X509ExtendedTrustManager() {
    private fun verify(chain: Array<out X509Certificate>, delegateCheck: () -> Unit) {
        val fingerprint = chain.firstOrNull()?.let(::sha256Fingerprint)
            ?: throw CertificateException("Empty server certificate chain")
        if (pin != null) {
            if (!pin.equals(fingerprint, ignoreCase = true)) throw RejectedCertificate(fingerprint)
            return
        }
        try {
            delegateCheck()
        } catch (error: Exception) {
            throw RejectedCertificate(fingerprint, error)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
        verify(chain) { delegate.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: java.net.Socket) =
        verify(chain) {
            if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(chain, authType, socket)
            else delegate.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) =
        verify(chain) {
            if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(chain, authType, engine)
            else delegate.checkServerTrusted(chain, authType)
        }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: java.net.Socket) {
        if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(chain, authType, socket)
        else delegate.checkClientTrusted(chain, authType)
    }
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) {
        if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(chain, authType, engine)
        else delegate.checkClientTrusted(chain, authType)
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
