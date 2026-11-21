package ch.lkmc.pageworn.server

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@JvmInline
value class BaseUrl(val value: String) {
    private val url: HttpUrl get() = value.toHttpUrlOrNull()!!

    val origin: String get() = url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
    val isCleartext: Boolean get() = url.isHttps.not()

    fun resolve(relativePath: String): HttpUrl = url.resolve(relativePath.trimStart('/'))!!

    fun contains(other: HttpUrl): Boolean =
        url.scheme == other.scheme && url.host == other.host && url.port == other.port &&
            other.encodedPath.startsWith(url.encodedPath)
}

sealed interface BaseUrlParse {
    data class Valid(val baseUrl: BaseUrl) : BaseUrlParse
    data class NeedsCleartextChoice(val baseUrl: BaseUrl) : BaseUrlParse
    data class Invalid(val reason: Reason) : BaseUrlParse

    enum class Reason { EMPTY, UNSUPPORTED_SCHEME, EMPTY_HOST, CREDENTIALS, QUERY, FRAGMENT, MALFORMED }
}

fun parseBaseUrl(input: String, cleartextAllowed: Boolean): BaseUrlParse {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return BaseUrlParse.Invalid(BaseUrlParse.Reason.EMPTY)
    if ('?' in trimmed) return BaseUrlParse.Invalid(BaseUrlParse.Reason.QUERY)
    if ('#' in trimmed) return BaseUrlParse.Invalid(BaseUrlParse.Reason.FRAGMENT)

    val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").find(trimmed)?.value
    if (scheme != null && scheme.dropLast(1).lowercase() !in setOf("http", "https")) {
        return BaseUrlParse.Invalid(BaseUrlParse.Reason.UNSUPPORTED_SCHEME)
    }
    val candidate = if (scheme == null) "https://$trimmed" else trimmed
    val authority = candidate.substringAfter("://").substringBefore('/')
    if ('@' in authority) return BaseUrlParse.Invalid(BaseUrlParse.Reason.CREDENTIALS)
    if (authority.isEmpty() || authority.startsWith(':')) return BaseUrlParse.Invalid(BaseUrlParse.Reason.EMPTY_HOST)

    val parsed = candidate.toHttpUrlOrNull() ?: return BaseUrlParse.Invalid(BaseUrlParse.Reason.MALFORMED)
    val path = parsed.encodedPath.trimEnd('/') + "/"
    val normalized = BaseUrl(parsed.newBuilder().encodedPath(path).build().toString())
    return if (normalized.isCleartext && !cleartextAllowed) {
        BaseUrlParse.NeedsCleartextChoice(normalized)
    } else {
        BaseUrlParse.Valid(normalized)
    }
}
