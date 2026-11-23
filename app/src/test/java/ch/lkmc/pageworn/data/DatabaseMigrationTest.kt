package ch.lkmc.pageworn.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.PagewornDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A23: an upgrade from each exported schema keeps downloads, saved places and the cached catalog.
 * The old database is made from its exported schema, and Room checks the migrated one on opening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var db: PagewornDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(PagewornDatabase.NAME)
    }

    @Test
    fun a23VersionOneKeepsDownloadsPlacesAndCatalog() = runBlocking<Unit> {
        createVersion1().use { old ->
            old.execSQL("INSERT INTO profile VALUES ('p', 'account', 'listener', NULL, 'lib', '2.38.0', 1, 1)")
            old.execSQL("INSERT INTO library VALUES ('p', 'lib', 'Books', 0, 5, 'published')")
            old.execSQL("INSERT INTO catalog_book VALUES ('p', 'lib', 'book', 'Apricot', 'Zora Hale', NULL, 0, 7, 60000, 100, 'published')")
            old.execSQL("INSERT INTO refresh_attempt VALUES ('attempt', 'p', 'lib', 1, 4, 'RUNNING', 0, 1)")
            old.execSQL("INSERT INTO catalog_staging VALUES ('attempt', 'book', 'Apricot', 'Zora Hale', NULL, 0, 7, 60000, 100, 1)")
            old.execSQL("INSERT INTO local_edition VALUES ('p', 'book', 'gen', 'print', 'READY', 60000, 'dir', NULL, 'Apricot', 'Zora Hale', NULL, NULL, NULL, 3, 100)")
            old.execSQL("INSERT INTO book_progress VALUES ('p', 'book', 42000, 60000, 'print', 0, 9, 'TIMED', 8, 2, 3, 0)")
        }

        val upgraded = PagewornDatabase.build(context).also { db = it }

        val place = checkNotNull(upgraded.progress().get("p", "book"))
        assertThat(place.positionMs).isEqualTo(42_000)
        assertThat(place.stopKind).isEqualTo(StopKind.TIMED)
        assertThat(place.updatedAt).isEqualTo(9)
        val edition = checkNotNull(upgraded.editions().edition("p", "book"))
        assertThat(edition.generationId).isEqualTo("gen")
        assertThat(edition.state).isEqualTo(EditionState.READY)
        val cached = upgraded.catalog().observe("p", "lib", "").first().single()
        assertThat(cached.title).isEqualTo("Apricot")
        assertThat(cached.updatedAt).isEqualTo(7)
        // Unknown until the next refresh, rather than guessed from when the book last changed.
        assertThat(cached.addedAt).isNull()
        assertThat(upgraded.catalog().staged("attempt").single().addedAt).isNull()
    }

    private fun createVersion1(): SQLiteDatabase {
        val schema = File("schemas/${PagewornDatabase::class.java.name}/1.json").readText()
        val database = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(PagewornDatabase.NAME).apply { parentFile?.mkdirs() }
        return SQLiteDatabase.openOrCreateDatabase(file, null).also { old ->
            for (entity in database.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                val creations = listOf(entity) + entity["indices"]?.jsonArray?.map { it.jsonObject }.orEmpty()
                creations.forEach { old.execSQL(it.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            database.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.version = 1
        }
    }
}
