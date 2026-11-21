package ch.lkmc.pageworn.testing

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * The control API of one `server-fixture/fault_proxy.py` instance, reached from the emulator
 * through the host alias. A rule applies to the next [count] requests whose path matches
 * [match] (0 means every one); the first active matching rule wins.
 */
class FaultProxy(private val controlPort: Int) {
    data class Rule(val json: JSONObject)

    /** One request as the proxy saw it; it never records headers other than the range pair. */
    data class Seen(val method: String, val path: String, val range: String?, val ifRange: String?, val rule: String?, val status: Int?, val bodyBytes: Long)

    fun rules(vararg rules: Rule) {
        post("/rules", JSONArray(rules.map { it.json }).toString())
    }

    fun reset() {
        post("/reset", "")
    }

    fun log(): List<Seen> {
        val requests = JSONObject(request("GET", "/log", null)).getJSONArray("requests")
        return (0 until requests.length()).map { index ->
            val r = requests.getJSONObject(index)
            Seen(
                r.getString("method"), r.getString("path"), r.optStringOrNull("range"), r.optStringOrNull("if_range"),
                r.optStringOrNull("rule"), if (r.isNull("status")) null else r.getInt("status"), r.optLong("body_bytes"),
            )
        }
    }

    /** The audio requests, in arrival order. */
    fun downloads(): List<Seen> = log().filter { it.path.contains(DOWNLOAD_PATH) }

    private fun post(path: String, body: String) {
        request("POST", path, body)
    }

    private fun request(method: String, path: String, body: String?): String {
        val connection = URL("http://$HOST:$controlPort$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            check(connection.responseCode == 200) { "fault proxy $controlPort answered ${connection.responseCode} to $method $path" }
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    companion object {
        /** The host as the emulator sees it. */
        const val HOST = "10.0.2.2"
        const val DOWNLOAD_PATH = "/download"

        /** The path of one track's download route. */
        fun fileDownload(fileId: String) = "/file/$fileId/download"

        private fun rule(mode: String, match: String, count: Int, extra: JSONObject.() -> Unit = {}) =
            Rule(JSONObject().put("mode", mode).put("match", match).put("count", count).apply(extra))

        /** Closes the connection after [bytes] body bytes. */
        fun drop(match: String, bytes: Long, count: Int = 1) = rule("drop", match, count) { put("bytes", bytes) }

        /** Strips Range and If-Range, so the server answers with the whole file. */
        fun ignoreRange(match: String, count: Int = 1) = rule("ignore_range", match, count)

        /** Sends the body at most [rate] bytes per second. */
        fun throttle(match: String, rate: Long, count: Int = 0) = rule("throttle", match, count) { put("rate", rate) }

        /** Answers with [code] and no body, without asking the server. */
        fun status(match: String, code: Int, count: Int = 1) = rule("status", match, count) { put("code", code) }

        /** Closes the connection without an answer, as an address that stopped answering does. */
        fun refuse(match: String, count: Int = 0) = rule("refuse", match, count)
    }
}
