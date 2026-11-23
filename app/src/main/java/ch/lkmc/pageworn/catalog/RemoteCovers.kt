package ch.lkmc.pageworn.catalog

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Disk cache for the Library's remote cover thumbnails.
 *
 * File names carry a SHA-256 digest of the item ID, never the ID itself. A cached file is
 * returned without a request; a failed request is remembered for [NEGATIVE_TTL_MS] so
 * scrolling past an unreachable server does not repeat it.
 */
class RemoteCovers(
    private val dir: File,
    private val fetch: suspend (itemId: String, widthPx: Int, updatedAt: Long) -> ByteArray?,
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failures = ConcurrentHashMap<String, Long>()

    // The application owns one cache. Sweep interrupted writes once, before any writer in
    // this process can create a temp file. No timestamp can distinguish a slow writer.
    private val writeDirectory by lazy {
        dir.listFiles { _, name -> name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX) }
            ?.forEach { if (it.isFile) it.delete() }
        dir
    }

    suspend fun thumbnail(itemId: String, updatedAt: Long, widthPx: Int): File? {
        val key = key(itemId, updatedAt, widthPx)
        if (failureActive(key)) return null
        val lock = locks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            if (failureActive(key)) return@withLock null
            val cached = withContext(Dispatchers.IO) {
                fileFor(itemId, updatedAt, widthPx).takeIf(File::isFile)
            }
            if (cached != null) {
                withContext(Dispatchers.IO) { touch(cached) }
                return@withLock cached
            }
            val bytes = fetch(itemId, widthPx, updatedAt)
            if (bytes == null) {
                failures[key] = nowMs() + NEGATIVE_TTL_MS
                return@withLock null
            }
            failures.remove(key)
            withContext(Dispatchers.IO) { write(itemId, updatedAt, widthPx, bytes) }
        }
    }

    private fun failureActive(key: String): Boolean {
        val until = failures[key] ?: return false
        return nowMs() < until
    }

    private fun key(itemId: String, updatedAt: Long, widthPx: Int): String =
        "${digest(itemId)}-$updatedAt-$widthPx"

    private fun fileFor(itemId: String, updatedAt: Long, widthPx: Int): File =
        File(dir, "${key(itemId, updatedAt, widthPx)}$EXTENSION")

    private fun touch(file: File) {
        runCatching { file.setLastModified(nowMs()) }
    }

    private fun write(itemId: String, updatedAt: Long, widthPx: Int, bytes: ByteArray): File? {
        val directory = writeDirectory
        directory.mkdirs()
        val target = fileFor(itemId, updatedAt, widthPx)
        val temp = try {
            File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
        } catch (_: IOException) {
            return null
        }
        return try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target) && !target.isFile) {
                temp.delete()
                return null
            }
            temp.delete()
            deleteOlderRevisions(itemId, updatedAt)
            evictIfNeeded()
            target.takeIf(File::isFile)
        } catch (_: IOException) {
            temp.delete()
            null
        }
    }

    private fun deleteOlderRevisions(itemId: String, updatedAt: Long) {
        val prefix = "${digest(itemId)}-"
        dir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || !file.name.startsWith(prefix)) return@forEach
            val revision = file.name.removePrefix(prefix).substringBefore('-').toLongOrNull() ?: return@forEach
            if (revision < updatedAt) file.delete()
        }
    }

    private fun evictIfNeeded() {
        // Other keys can be writing temporary files while this thumbnail is published.
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(EXTENSION) && it.isFile }
        var total = files.sumOf(File::length)
        if (total <= maxBytes) return
        for (file in files.sortedBy(File::lastModified)) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun digest(itemId: String): String =
        MessageDigest.getInstance("SHA-256").digest(itemId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXTENSION = ".webp"
        const val TEMP_PREFIX = "cover-"
        const val TEMP_SUFFIX = ".tmp"
        const val NEGATIVE_TTL_MS = 10L * 60 * 1000
    }
}
