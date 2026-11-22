package ch.lkmc.pageworn

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.EditionManifest
import ch.lkmc.pageworn.data.ManifestTrack
import ch.lkmc.pageworn.data.db.ProfileEntity
import java.io.File

/** Downloaded books in an app's own stores, for tests of the surfaces that show them. */
class PhoneBooks(private val container: AppContainer) {
    suspend fun download(itemId: String, title: String = itemId, durationMs: Long = DURATION_MS): BookKey {
        container.database.profiles().upsert(ProfileEntity(PROFILE, "account", "Listener", null, null, "2.38.0", 1))
        container.editionFiles.ensureRoots()
        val generation = "generation-$itemId"
        val staged = container.editionFiles.staging(generation).also { it.mkdirs() }
        File(staged, AUDIO).writeBytes(byteArrayOf(1))
        container.editionStore.publish(
            EditionManifest(
                generationId = generation, jobId = null, profileId = PROFILE, itemId = itemId, fingerprint = "print-$itemId", title = title,
                authors = "Elin Ward", tracks = listOf(ManifestTrack(0, "file", 1, 1, AUDIO, 0, durationMs, extension = "mp3")), chapters = emptyList(),
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
        private const val AUDIO = "000.mp3"
    }
}
