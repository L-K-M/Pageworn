package ch.lkmc.pageworn.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.formats.MediaProbe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug only. Seeds [DemoLibrary] from fixtures pushed to the app's external files directory, then
 * opens the app:
 *
 *     adb push fixtures/out/assets/fixtures/. /sdcard/Android/data/ch.lkmc.pageworn/files/fixtures/
 *     adb shell am start -n ch.lkmc.pageworn/.debug.DemoActivity
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = File(getExternalFilesDir(null), "fixtures")
        val container = (application as PagewornApp).container
        val probe = MediaProbe(this)
        val source = object : DemoLibrary.FixtureSource {
            override fun files(fixtureId: String): List<File> =
                File(root, fixtureId).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
                    ?: error("Push the fixtures to $root first")

            // The probe runs its player on the main thread, so seeding runs on a worker thread.
            override fun durationMs(file: File): Long =
                runBlocking { probe.probe(file) }.durationMs ?: error("${file.name} did not open")
        }
        thread(name = "demo-seed") {
            runCatching { runBlocking { DemoLibrary.seed(this@DemoActivity, container, source) } }
                .onFailure { Log.e("DemoActivity", "Seeding failed", it) }
            runOnUiThread {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }
}
