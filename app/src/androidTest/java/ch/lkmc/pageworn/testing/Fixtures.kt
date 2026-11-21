package ch.lkmc.pageworn.testing

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileNotFoundException

/**
 * Generated audio fixtures (fixtures/generate.py), packaged in the test APK's assets under
 * `fixtures/<id>/`. Tests refer to fixtures by manifest ID, never by a machine path.
 */
object Fixtures {
    data class FixtureFile(val name: String, val extension: String, val container: String, val codec: String, val durationMs: Long)
    data class Cue(val atMs: Long, val freqHz: Int)
    data class Fixture(val id: String, val generated: Boolean, val files: List<FixtureFile>, val cues: List<Cue>, val nominalDurationMs: Long, val raw: JsonObject)

    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context
    private val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val manifest: List<Fixture> by lazy {
        val text = try {
            testContext.assets.open("fixtures/manifest.json").bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            throw AssertionError("Audio fixtures are missing from the test APK. Run `python3 fixtures/generate.py` first.", e)
        }
        val root = Json.parseToJsonElement(text).jsonObject
        root.getValue("fixtures").jsonArray.map { element ->
            val o = element.jsonObject
            Fixture(
                id = o.getValue("id").jsonPrimitive.content,
                generated = o["status"]?.jsonPrimitive?.content != "not-generated",
                files = (o["files"] as? JsonArray).orEmpty().map { f ->
                    val fo = f.jsonObject
                    FixtureFile(
                        name = fo.getValue("name").jsonPrimitive.content,
                        extension = fo["extension"]?.jsonPrimitive?.content ?: fo.getValue("name").jsonPrimitive.content.substringAfterLast('.'),
                        container = fo["container"]?.jsonPrimitive?.content ?: "",
                        codec = fo["codec"]?.jsonPrimitive?.content ?: "",
                        durationMs = fo["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                },
                cues = (o["cues"] as? JsonArray).orEmpty().map { c ->
                    Cue(c.jsonObject.getValue("at_ms").jsonPrimitive.long, c.jsonObject.getValue("freq_hz").jsonPrimitive.long.toInt())
                },
                nominalDurationMs = o["nominal_duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
                raw = o,
            )
        }
    }

    fun fixture(id: String): Fixture =
        manifest.firstOrNull { it.id == id } ?: throw AssertionError("Fixture $id is not in the manifest")

    /** Copies a fixture's files into app-private storage and returns them in manifest order. */
    fun install(id: String, into: File = File(targetContext.filesDir, "test-fixtures/$id")): List<File> {
        val f = fixture(id)
        check(f.generated) { "Fixture $id was not generated" }
        into.mkdirs()
        return f.files.map { file ->
            val out = File(into, file.name)
            if (!out.isFile) {
                testContext.assets.open("fixtures/$id/${file.name}").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }
    }
}
