package ch.lkmc.pageworn.transfer

import okhttp3.Headers
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ResumeValidator(val value: String, val isDate: Boolean)

/** An HTTP date is usable for If-Range only when it is at least a minute old at response time. */
fun resumeValidator(headers: Headers): ResumeValidator? {
    val etag = headers["ETag"]
    if (etag != null && !etag.startsWith("W/", ignoreCase = true) && etag.startsWith('"') && etag.endsWith('"')) {
        return ResumeValidator(etag, false)
    }
    val modified = headers["Last-Modified"] ?: return null
    val date = parseHttpDate(headers["Date"]) ?: return null
    val modifiedDate = parseHttpDate(modified) ?: return null
    if (date - modifiedDate < 60_000) return null
    return ResumeValidator(modified, true)
}

private fun parseHttpDate(value: String?): Long? = try {
    value?.let { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
} catch (_: Exception) {
    null
}
