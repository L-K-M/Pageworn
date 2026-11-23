package ch.lkmc.pageworn

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.EditionManifest
import ch.lkmc.pageworn.data.ManifestTrack
import ch.lkmc.pageworn.data.db.ProfileEntity
import java.io.File

/** Downloaded books in an app's own stores, for tests of the surfaces that show them. */
class PhoneBooks(private val container: AppContainer) {
    /** [audio] is one byte unless the test plays it. A repair is a download in a new [generation]. */
    suspend fun download(
        itemId: String,
        title: String = itemId,
        durationMs: Long = DURATION_MS,
        audio: ByteArray = byteArrayOf(1),
        extension: String = "mp3",
        generation: String = "generation-$itemId",
    ): BookKey {
        container.database.profiles().upsert(ProfileEntity(PROFILE, "account", "Listener", null, null, "2.38.0", 1))
        container.editionFiles.ensureRoots()
        val staged = container.editionFiles.staging(generation).also { it.mkdirs() }
        val file = "000.$extension"
        File(staged, file).writeBytes(audio)
        container.editionStore.publish(
            EditionManifest(
                generationId = generation, jobId = null, profileId = PROFILE, itemId = itemId, fingerprint = "print-$itemId", title = title,
                authors = "Elin Ward", tracks = listOf(ManifestTrack(0, "file", 1, audio.size.toLong(), file, 0, durationMs, extension = extension)), chapters = emptyList(),
            ),
        )
        return BookKey(PROFILE, itemId)
    }

    suspend fun place(key: BookKey, positionMs: Long, durationMs: Long = DURATION_MS) {
        container.progressStore.saveOutsideSession(key, positionMs, durationMs, "print-${key.itemId}", StopKind.EXACT)
    }

    companion object {
        /** 4 h 20 min, as the player's mockup shows. */
        const val DURATION_MS = 15_600_000L
        private const val PROFILE = "profile"
    }
}
