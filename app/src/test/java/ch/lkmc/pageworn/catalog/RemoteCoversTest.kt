package ch.lkmc.pageworn.catalog

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteCoversTest {
    @get:Rule val folder = TemporaryFolder()

    private fun covers(
        dir: File,
        fetch: suspend (String, Int, Long) -> ByteArray?,
        maxBytes: Long = 50L * 1024 * 1024,
        nowMs: () -> Long = { 0L },
    ) = RemoteCovers(dir, fetch, maxBytes, nowMs)

    @Test fun cachedFileIsReturnedWithoutFetchingAgain() = runBlocking {
        val dir = folder.newFolder()
        var calls = 0
        val covers = covers(dir, fetch = { _, _, _ -> calls++; byteArrayOf(1, 2, 3) })
        val first = covers.thumbnail("item-1", 10L, 200)
        val second = covers.thumbnail("item-1", 10L, 200)
        assertThat(second).isEqualTo(first)
        assertThat(calls).isEqualTo(1)
        assertThat(first!!.readBytes().asList()).containsExactly(1.toByte(), 2.toByte(), 3.toByte()).inOrder()
        assertThat(dir.listFiles()).hasLength(1)
    }

    @Test fun aMissIsFetchedOffTheCallersThread() {
        // Covers load from composition, on the main thread. The fetch chooses a route and reads
        // each address's credential from the Keystore, so it must not run on the caller.
        val dir = folder.newFolder()
        val fetchThread = AtomicReference<Thread>()
        val covers = covers(dir, fetch = { _, _, _ -> fetchThread.set(Thread.currentThread()); byteArrayOf(1) })
        val callerThread = AtomicReference<Thread>()
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller-main").also(callerThread::set) }
        try {
            val file = runBlocking(caller.asCoroutineDispatcher()) { covers.thumbnail("item-1", 10L, 200) }
            assertThat(file).isNotNull()
        } finally {
            caller.shutdown()
        }
        assertThat(fetchThread.get()).isNotNull()
        assertThat(fetchThread.get()).isNotSameInstanceAs(callerThread.get())
    }

    @Test fun concurrentCallsForOneKeyShareOneFetch() = runBlocking {
        val dir = folder.newFolder()
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val covers = covers(dir, fetch = { _, _, _ ->
            calls++
            started.complete(Unit)
            release.await()
            byteArrayOf(7)
        })
        val first = async(Dispatchers.IO) { covers.thumbnail("item-1", 10L, 200) }
        started.await()
        val second = async(Dispatchers.IO) { covers.thumbnail("item-1", 10L, 200) }
        release.complete(Unit)
        val results = awaitAll(first, second)
        assertThat(calls).isEqualTo(1)
        assertThat(results[0]).isNotNull()
        assertThat(results[1]).isEqualTo(results[0])
    }

    @Test fun aNewerRevisionDeletesOlderFiles() = runBlocking {
        val dir = folder.newFolder()
        val covers = covers(dir, fetch = { _, _, updatedAt -> byteArrayOf((updatedAt % 256).toByte()) })
        val old = covers.thumbnail("item-1", 10L, 200)!!
        val oldOtherWidth = covers.thumbnail("item-1", 10L, 300)!!
        val newer = covers.thumbnail("item-1", 20L, 200)!!
        assertThat(old.exists()).isFalse()
        assertThat(oldOtherWidth.exists()).isFalse()
        assertThat(newer.exists()).isTrue()
    }

    @Test fun failedFetchIsNotRetriedForTenMinutes() = runBlocking {
        val dir = folder.newFolder()
        var calls = 0
        var now = 0L
        val covers = covers(dir, fetch = { _, _, _ -> calls++; null }, nowMs = { now })
        assertThat(covers.thumbnail("item-1", 10L, 200)).isNull()
        assertThat(covers.thumbnail("item-1", 10L, 200)).isNull()
        assertThat(calls).isEqualTo(1)
        assertThat(dir.listFiles()).hasLength(0)
        now = 10L * 60 * 1000 - 1
        assertThat(covers.thumbnail("item-1", 10L, 200)).isNull()
        assertThat(calls).isEqualTo(1)
        now = 10L * 60 * 1000
        assertThat(covers.thumbnail("item-1", 10L, 200)).isNull()
        assertThat(calls).isEqualTo(2)
    }

    @Test fun evictionRemovesTheLeastRecentlyUsedFileFirst() = runBlocking {
        val dir = folder.newFolder()
        val covers = covers(dir, fetch = { _, _, _ -> ByteArray(100) }, maxBytes = 250)
        val first = covers.thumbnail("item-1", 1L, 200)!!
        first.setLastModified(1_000)
        val second = covers.thumbnail("item-2", 2L, 200)!!
        second.setLastModified(2_000)
        val third = covers.thumbnail("item-3", 3L, 200)!!
        assertThat(first.exists()).isFalse()
        assertThat(second.exists()).isTrue()
        assertThat(third.exists()).isTrue()
        val files = requireNotNull(dir.listFiles())
        assertThat(files.sumOf { it.length() }).isAtMost(250L)
    }

    @Test fun evictionLeavesAnUnpublishedThumbnailAlone() = runBlocking {
        val dir = folder.newFolder()
        val covers = covers(dir, fetch = { _, _, _ -> ByteArray(100) }, maxBytes = 150)
        val victim = checkNotNull(covers.thumbnail("old-item", 1L, 200))
        victim.setLastModified(2_000)
        val staged = File.createTempFile("cover-", ".tmp", dir)
        staged.writeBytes(ByteArray(100))
        staged.setLastModified(1_000)

        val published = covers.thumbnail("another-item", 1L, 200)

        assertThat(victim.exists()).isFalse()
        assertThat(staged.length()).isEqualTo(100L)
        assertThat(published?.length()).isEqualTo(100L)
    }

    @Test fun firstWriteReclaimsAnInterruptedWritersTemporaryFile() = runBlocking {
        val dir = folder.newFolder()
        val orphan = File.createTempFile("cover-", ".tmp", dir)
        val covers = covers(dir, fetch = { _, _, _ -> byteArrayOf(1) })

        assertThat(covers.thumbnail("item", 1L, 200)).isNotNull()
        assertThat(orphan.exists()).isFalse()
    }

    @Test fun cachedFileNameHidesTheRawItemId() = runBlocking {
        val dir = folder.newFolder()
        val itemId = "24ce6539-29bf-4b23-b239-22a38a406464"
        val covers = covers(dir, fetch = { _, _, _ -> byteArrayOf(1) })
        val file = covers.thumbnail(itemId, 10L, 200)!!
        assertThat(file.name).doesNotContain(itemId)
        assertThat(file.name).matches("[0-9a-f]{64}-10-200\\.webp")
    }
}
