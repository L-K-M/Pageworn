package ch.lkmc.pageworn.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The listening library. Schema changes are additive migrations with an upgrade test; a schema
 * mismatch must never fall back to destructive migration, which would silently delete downloads
 * and saved places.
 */
@Database(
    entities = [
        ProfileEntity::class,
        AddressEntity::class,
        LibraryEntity::class,
        CatalogBookEntity::class,
        RefreshAttemptEntity::class,
        CatalogStagingEntity::class,
        LocalEditionEntity::class,
        TrackEntity::class,
        ChapterEntity::class,
        PendingRemovalEntity::class,
        TransferEntity::class,
        TransferFileEntity::class,
        BookProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PagewornDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun addresses(): AddressDao
    abstract fun libraries(): LibraryDao
    abstract fun catalog(): CatalogDao
    abstract fun editions(): EditionDao
    abstract fun transfers(): TransferDao
    abstract fun progress(): ProgressDao

    companion object {
        const val NAME = "pageworn.db"

        /** Additive migrations, oldest first. Never add a destructive fallback. */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>()

        fun build(context: Context, inMemory: Boolean = false): PagewornDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, PagewornDatabase::class.java)
            } else {
                Room.databaseBuilder(context, PagewornDatabase::class.java, NAME)
            }
            return builder.addMigrations(*MIGRATIONS).build()
        }
    }
}
