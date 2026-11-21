package ch.lkmc.pageworn.testing

import android.os.Process
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.playback.ProgressWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A progress writer that can hold writes back and deliver them later, to stand in for a delayed
 * position callback (A10, A10b). Writes it does not hold go to [delegate] at once.
 */
class HoldingWriter(private val delegate: ProgressWriter) : ProgressWriter {
    data class Write(
        val key: BookKey,
        val token: ProgressStore.Token,
        val positionMs: Long,
        val durationMs: Long,
        val fingerprint: String?,
        val stopKind: StopKind,
        val finished: Boolean,
        val listened: Boolean,
    )

    /** Writes for which this returns true are held until [deliverHeld]. */
    @Volatile var holdIf: ((Write) -> Boolean)? = null

    val held = CopyOnWriteArrayList<Write>()
    val passed = CopyOnWriteArrayList<Write>()

    override fun write(
        key: BookKey,
        token: ProgressStore.Token,
        positionMs: Long,
        durationMs: Long,
        fingerprint: String?,
        stopKind: StopKind,
        finished: Boolean,
        listened: Boolean,
    ) {
        val write = Write(key, token, positionMs, durationMs, fingerprint, stopKind, finished, listened)
        if (holdIf?.invoke(write) == true) {
            held += write
            return
        }
        passed += write
        delegate.write(key, token, positionMs, durationMs, fingerprint, stopKind, finished, listened)
    }

    fun deliverHeld() {
        holdIf = null
        val writes = held.toList()
        held.clear()
        for (w in writes) delegate.write(w.key, w.token, w.positionMs, w.durationMs, w.fingerprint, w.stopKind, w.finished, w.listened)
    }
}

/**
 * Hand-off between the two halves of a restart case. The first half records what it observed and
 * its process ID; the second half runs in a later instrumentation run, which Android starts in a
 * new process, and skips itself when the process did not restart (as in a plain Gradle run of the
 * whole suite, where both halves share one process). `am instrument` ends the first half's process
 * without lifecycle callbacks, which is the abrupt death the cases describe.
 */
object RestartMarker {
    private val file get() = File(TestApp.context.filesDir, "restart-marker.json")

    fun write(case: String, values: Map<String, Long>) {
        val o = JsonObject(
            values.mapValues { JsonPrimitive(it.value) } + mapOf(
                "case" to JsonPrimitive(case),
                "pid" to JsonPrimitive(Process.myPid().toLong()),
            ),
        )
        file.writeText(o.toString())
    }

    /** The values the first half of [case] recorded; skips the test unless this is a new process. */
    fun readAfterRestart(case: String): Map<String, Long> {
        assumeTrue("Run the first half of $case first, in a separate instrumentation run", file.isFile)
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        assumeTrue("The marker belongs to another case", o["case"]?.jsonPrimitive?.content == case)
        assumeTrue("The process did not restart since the first half", o.getValue("pid").jsonPrimitive.long != Process.myPid().toLong())
        return o.filterKeys { it != "case" && it != "pid" }.mapValues { it.value.jsonPrimitive.long }
    }

    fun clear() {
        file.delete()
    }
}
