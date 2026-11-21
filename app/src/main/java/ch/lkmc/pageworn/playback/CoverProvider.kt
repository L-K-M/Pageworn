package ch.lkmc.pageworn.playback

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.BookKey
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Serves the cover stored with a published edition as a `content://` URI, because the car host
 * loads list artwork by URI and accepts no bitmaps. A book without a cover gets the cloth fallback.
 * The provider is not exported: each caller receives a read grant for the URIs it was given.
 */
class CoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read-only")
        val context = context ?: throw FileNotFoundException(uri.toString())
        val segments = uri.pathSegments
        if (segments.size != 3) throw FileNotFoundException(uri.toString())
        val key = runCatching { BookKey(segments[0], segments[1]) }.getOrNull() ?: throw FileNotFoundException(uri.toString())
        val container = (context.applicationContext as PagewornApp).container
        val file = runBlocking { container.coverFileFor(key) }
        if (file != null && file.isFile) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        // The packaged PNG is also available as a file for clients of the provider.
        @SuppressLint("ResourceType")
        val fallback = context.resources.openRawResourceFd(R.drawable.pw_cover_cloth)
        return fallback
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun authority(context: Context) = "${context.packageName}.covers"

        /**
         * URI of the cover of [key]'s edition [generationId]. The generation in the path changes the URI
         * when a new edition is published, so hosts do not reuse a stale image. When [grantTo] names
         * another package, that package receives a read grant for this URI.
         */
        fun uriFor(context: Context, key: BookKey, generationId: String, grantTo: String?): Uri {
            val uri = Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(key.profileId)
                .appendPath(key.itemId)
                .appendPath(generationId)
                .build()
            if (grantTo != null && grantTo != context.packageName) {
                context.grantUriPermission(grantTo, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return uri
        }
    }
}
