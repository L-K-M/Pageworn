package ch.lkmc.pageworn.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile LIMIT 1")
    suspend fun current(): ProfileEntity?

    @Query("SELECT * FROM profile LIMIT 1")
    fun observe(): Flow<ProfileEntity?>

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("UPDATE profile SET chosenLibraryId = :libraryId WHERE profileId = :profileId")
    suspend fun setChosenLibrary(profileId: String, libraryId: String?)

    @Query("UPDATE profile SET canDownload = :canDownload WHERE profileId = :profileId")
    suspend fun setCanDownload(profileId: String, canDownload: Boolean)
}

@Dao
interface AddressDao {
    @Query("SELECT * FROM address WHERE profileId = :profileId ORDER BY priority, addressId")
    suspend fun forProfile(profileId: String): List<AddressEntity>

    @Query("SELECT * FROM address WHERE profileId = :profileId ORDER BY priority, addressId")
    fun observe(profileId: String): Flow<List<AddressEntity>>

    @Query("SELECT * FROM address WHERE addressId = :addressId")
    suspend fun get(addressId: String): AddressEntity?

    @Upsert
    suspend fun upsert(address: AddressEntity)

    @Upsert
    suspend fun upsertAll(addresses: List<AddressEntity>)

    @Query("DELETE FROM address WHERE addressId = :addressId")
    suspend fun delete(addressId: String)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library WHERE profileId = :profileId ORDER BY displayOrder, libraryId")
    fun observe(profileId: String): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM library WHERE profileId = :profileId ORDER BY displayOrder, libraryId")
    suspend fun forProfile(profileId: String): List<LibraryEntity>

    @Query("SELECT * FROM library WHERE profileId = :profileId AND libraryId = :libraryId")
    suspend fun get(profileId: String, libraryId: String): LibraryEntity?

    @Query("SELECT * FROM library WHERE profileId = :profileId AND libraryId = :libraryId")
    fun observeOne(profileId: String, libraryId: String): Flow<LibraryEntity?>

    @Upsert
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM library WHERE profileId = :profileId AND libraryId NOT IN (:keep)")
    suspend fun deleteOthers(profileId: String, keep: List<String>)

    @Query("UPDATE library SET lastRefreshAt = :at, publishedRefreshId = :refreshId WHERE profileId = :profileId AND libraryId = :libraryId")
    suspend fun markPublished(profileId: String, libraryId: String, refreshId: String, at: Long)
}

@Dao
interface CatalogDao {
    @Query(
        """SELECT * FROM catalog_book WHERE profileId = :profileId AND libraryId = :libraryId
           AND (:query = '' OR title LIKE '%' || :query || '%' ESCAPE '\' OR authors LIKE '%' || :query || '%' ESCAPE '\')
           ORDER BY sortIndex, title COLLATE NOCASE, itemId""",
    )
    fun observe(profileId: String, libraryId: String, query: String): Flow<List<CatalogBookEntity>>

    @Query("SELECT * FROM catalog_book WHERE profileId = :profileId AND itemId = :itemId LIMIT 1")
    suspend fun find(profileId: String, itemId: String): CatalogBookEntity?

    @Query("SELECT * FROM catalog_book WHERE profileId = :profileId AND itemId = :itemId LIMIT 1")
    fun observeOne(profileId: String, itemId: String): Flow<CatalogBookEntity?>

    @Query("DELETE FROM catalog_book WHERE profileId = :profileId AND libraryId = :libraryId")
    suspend fun clearLibrary(profileId: String, libraryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<CatalogBookEntity>)

    // Refresh staging.
    @Insert
    suspend fun insertAttempt(attempt: RefreshAttemptEntity)

    @Query("SELECT * FROM refresh_attempt WHERE refreshId = :refreshId")
    suspend fun attempt(refreshId: String): RefreshAttemptEntity?

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM refresh_attempt WHERE profileId = :profileId AND libraryId = :libraryId")
    suspend fun maxSequence(profileId: String, libraryId: String): Long

    @Query("SELECT * FROM refresh_attempt WHERE profileId = :profileId AND libraryId = :libraryId ORDER BY sequence DESC LIMIT 1")
    fun observeLatestAttempt(profileId: String, libraryId: String): Flow<RefreshAttemptEntity?>

    @Query("UPDATE refresh_attempt SET state = :state, serverTotal = COALESCE(:total, serverTotal) WHERE refreshId = :refreshId")
    suspend fun setAttemptState(refreshId: String, state: RefreshState, total: Int? = null)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun stage(entries: List<CatalogStagingEntity>)

    @Query("SELECT * FROM catalog_staging WHERE refreshId = :refreshId ORDER BY sortIndex")
    suspend fun staged(refreshId: String): List<CatalogStagingEntity>

    @Query(
        """SELECT * FROM catalog_staging WHERE refreshId = :refreshId AND playable = 1
           AND (:query = '' OR title LIKE '%' || :query || '%' ESCAPE '\' OR authors LIKE '%' || :query || '%' ESCAPE '\')
           ORDER BY sortIndex""",
    )
    fun observeStaged(refreshId: String, query: String): Flow<List<CatalogStagingEntity>>

    @Query("SELECT COUNT(*) FROM catalog_staging WHERE refreshId = :refreshId")
    suspend fun stagedCount(refreshId: String): Int

    @Query("DELETE FROM catalog_staging WHERE refreshId = :refreshId")
    suspend fun discardStaging(refreshId: String)

    @Query("DELETE FROM catalog_staging WHERE refreshId IN (SELECT refreshId FROM refresh_attempt WHERE profileId = :profileId AND libraryId = :libraryId AND refreshId != :keep)")
    suspend fun discardOtherStaging(profileId: String, libraryId: String, keep: String)
}

@Dao
interface EditionDao {
    @Query("SELECT * FROM local_edition WHERE profileId = :profileId AND itemId = :itemId")
    suspend fun edition(profileId: String, itemId: String): LocalEditionEntity?

    @Query("SELECT * FROM local_edition WHERE profileId = :profileId AND itemId = :itemId")
    fun observeEdition(profileId: String, itemId: String): Flow<LocalEditionEntity?>

    @Query("SELECT * FROM local_edition WHERE profileId = :profileId")
    fun observeAll(profileId: String): Flow<List<LocalEditionEntity>>

    @Query("SELECT * FROM local_edition")
    suspend fun all(): List<LocalEditionEntity>

    @Query("SELECT * FROM local_edition")
    fun observeEverything(): Flow<List<LocalEditionEntity>>

    @Query("SELECT * FROM track WHERE generationId = :generationId ORDER BY ordinal")
    suspend fun tracks(generationId: String): List<TrackEntity>

    @Query("SELECT * FROM chapter WHERE generationId = :generationId ORDER BY ordinal")
    suspend fun chapters(generationId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapter WHERE generationId = :generationId ORDER BY ordinal")
    fun observeChapters(generationId: String): Flow<List<ChapterEntity>>

    @Upsert
    suspend fun upsertEdition(edition: LocalEditionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM track WHERE generationId = :generationId")
    suspend fun deleteTracks(generationId: String)

    @Query("DELETE FROM chapter WHERE generationId = :generationId")
    suspend fun deleteChapters(generationId: String)

    @Query("DELETE FROM local_edition WHERE profileId = :profileId AND itemId = :itemId")
    suspend fun deleteEdition(profileId: String, itemId: String)

    @Query("UPDATE local_edition SET state = :state WHERE profileId = :profileId AND itemId = :itemId AND generationId = :generationId")
    suspend fun setState(profileId: String, itemId: String, generationId: String, state: EditionState)

    @Query("SELECT generationId FROM local_edition UNION SELECT stagingGenerationId FROM transfer WHERE activeMarker = 1")
    suspend fun referencedGenerations(): List<String>

    // Removal.
    @Query("SELECT * FROM pending_removal")
    suspend fun pendingRemovals(): List<PendingRemovalEntity>

    @Query("SELECT * FROM pending_removal")
    fun observePendingRemovals(): Flow<List<PendingRemovalEntity>>

    @Query("SELECT * FROM pending_removal WHERE profileId = :profileId AND itemId = :itemId")
    suspend fun pendingRemoval(profileId: String, itemId: String): PendingRemovalEntity?

    @Upsert
    suspend fun upsertPendingRemoval(removal: PendingRemovalEntity)

    @Query("DELETE FROM pending_removal WHERE profileId = :profileId AND itemId = :itemId")
    suspend fun clearPendingRemoval(profileId: String, itemId: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer WHERE profileId = :profileId AND itemId = :itemId AND activeMarker = 1")
    suspend fun active(profileId: String, itemId: String): TransferEntity?

    @Query("SELECT * FROM transfer WHERE jobId = :jobId")
    suspend fun get(jobId: String): TransferEntity?

    @Query("SELECT * FROM transfer WHERE activeMarker = 1 ORDER BY createdAt, jobId")
    suspend fun allActive(): List<TransferEntity>

    @Query("SELECT * FROM transfer WHERE activeMarker = 1 ORDER BY createdAt, jobId")
    fun observeActive(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfer WHERE state = 'CANCELLED'")
    suspend fun cancelled(): List<TransferEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transfer: TransferEntity)

    @Upsert
    suspend fun upsert(transfer: TransferEntity)

    @Query("DELETE FROM transfer WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("SELECT * FROM transfer_file WHERE jobId = :jobId ORDER BY ordinal")
    suspend fun files(jobId: String): List<TransferFileEntity>

    @Upsert
    suspend fun upsertFile(file: TransferFileEntity)

    @Upsert
    suspend fun upsertFiles(files: List<TransferFileEntity>)

    @Query("DELETE FROM transfer_file WHERE jobId = :jobId")
    suspend fun deleteFiles(jobId: String)

    /** Display journal only: never overwrites a file's validator or state. */
    @Query("UPDATE transfer_file SET bytesJournal = :bytes WHERE jobId = :jobId AND ordinal = :ordinal")
    suspend fun journalFile(jobId: String, ordinal: Int, bytes: Long)

    @Query("UPDATE transfer SET bytesDone = :bytes, updatedAt = :now WHERE jobId = :jobId AND state = 'TRANSFERRING'")
    suspend fun journalJob(jobId: String, bytes: Long, now: Long)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM book_progress WHERE profileId = :profileId AND itemId = :itemId")
    suspend fun get(profileId: String, itemId: String): BookProgressEntity?

    @Query("SELECT * FROM book_progress WHERE profileId = :profileId AND itemId = :itemId")
    fun observe(profileId: String, itemId: String): Flow<BookProgressEntity?>

    @Query("SELECT * FROM book_progress WHERE profileId = :profileId")
    fun observeAll(profileId: String): Flow<List<BookProgressEntity>>

    @Query("SELECT * FROM book_progress")
    fun observeEverything(): Flow<List<BookProgressEntity>>

    @Upsert
    suspend fun upsert(progress: BookProgressEntity)

    /**
     * Writes [progress] unless a write with the same or a newer (playbackGeneration, writeSequence)
     * already landed for the book. Returns whether it was written.
     */
    @Transaction
    suspend fun writeIfNewer(progress: BookProgressEntity): Boolean {
        val stored = get(progress.profileId, progress.itemId)
        if (stored != null) {
            val newer = progress.playbackGeneration > stored.playbackGeneration ||
                (progress.playbackGeneration == stored.playbackGeneration && progress.writeSequence > stored.writeSequence)
            if (!newer) return false
        }
        upsert(progress)
        return true
    }
}
