package ch.lkmc.pageworn.licences

import android.content.Context
import ch.lkmc.pageworn.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * The open-source notices shown under Settings.
 *
 * The dependency half is the AboutLibraries JSON generated from the app
 * variant's runtime classpath (`prepareLibraryDefinitionsRelease` for the
 * release build), so the release notices list the libraries it ships and their
 * licence texts. Spectral is a bundled font rather than a dependency, so its
 * copyright and licence text are appended from the checked-in raw resource.
 */
internal object LicenceNotices {

    /**
     * One `(name, licence notice)` pair per shipped dependency, plus Spectral.
     * The list is static for the process, so call it from a `remember` block.
     */
    internal fun entries(context: Context): List<Pair<String, String>> {
        val dependencyEntries = dependencyEntries(context)
        val spectral = spectralEntry(context)
        return (dependencyEntries + spectral).sortedWith(compareBy({ it.first.lowercase() }, { it.first }))
    }

    private fun dependencyEntries(context: Context): List<Pair<String, String>> {
        val root = JSONObject(readRawResource(context, R.raw.aboutlibraries))
        val libraries = root.optJSONArray("libraries") ?: JSONArray()
        val licenses = root.optJSONObject("licenses") ?: JSONObject()
        val entries = mutableListOf<Pair<String, String>>()

        for (index in 0 until libraries.length()) {
            val library = libraries.optJSONObject(index) ?: continue
            val name = library.optString("name").ifBlank { library.optString("uniqueId") }
            val coordinates = library.optString("uniqueId").ifBlank { name }
            val version = library.optString("artifactVersion")
            val label = if (version.isBlank()) "$name ($coordinates)" else "$name $version ($coordinates)"
            val notice = licenceNotice(library.optJSONArray("licenses"), licenses)
            entries += label to notice
        }
        return entries
    }

    private fun licenceNotice(keys: JSONArray?, licenses: JSONObject): String {
        if (keys == null || keys.length() == 0) return "Licence information is unavailable."
        val notices = mutableListOf<String>()
        for (index in 0 until keys.length()) {
            val key = keys.optString(index)
            val license = licenses.optJSONObject(key)
            val text = license?.optString("content").orEmpty().ifBlank { key }
            notices += text
        }
        return notices.distinct().joinToString("\n\n")
    }

    private fun spectralEntry(context: Context): Pair<String, String> {
        val notice = readRawResource(context, R.raw.spectral_ofl).trimEnd()
        return "Spectral" to notice
    }

    private fun readRawResource(context: Context, resource: Int): String =
        context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
}
