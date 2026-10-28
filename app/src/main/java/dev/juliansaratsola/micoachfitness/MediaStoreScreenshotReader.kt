package dev.juliansaratsola.micoachfitness

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class CapturedImage(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val relativePath: String?,
    val dataPath: String?,
    val takenAtMs: Long
)

/**
 * Reads recent images from MediaStore. On Android 11+ the DATA column may be
 * empty, so callers must classify with DISPLAY_NAME and RELATIVE_PATH too.
 */
class MediaStoreScreenshotReader(private val context: Context) {

    fun loadRecentCandidates(changedUri: Uri?, nowMs: Long = System.currentTimeMillis()): List<CapturedImage> {
        val specificId = imageIdFrom(changedUri)
        val cutoffSeconds = (nowMs / 1000L) - RECENT_WINDOW_SECONDS
        return try {
            query(specificId, cutoffSeconds, includeDataColumn = true)
        } catch (security: SecurityException) {
            Log.w(TAG, "MediaStore read denied", security)
            emptyList()
        } catch (illegal: IllegalArgumentException) {
            Log.w(TAG, "MediaStore query failed, retrying without DATA", illegal)
            try {
                query(specificId, cutoffSeconds, includeDataColumn = false)
            } catch (error: Exception) {
                Log.w(TAG, "MediaStore fallback query failed", error)
                emptyList()
            }
        }
    }

    private fun query(
        specificId: Long?,
        cutoffSeconds: Long,
        includeDataColumn: Boolean
    ): List<CapturedImage> {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.IS_PENDING)
            if (includeDataColumn) add(MediaStore.Images.Media.DATA)
        }.toTypedArray()

        val uri = if (specificId != null) {
            ContentUris.withAppendedId(collection, specificId)
        } else {
            collection
        }
        val selection: String?
        val args: Array<String>?
        if (specificId != null) {
            selection = null
            args = null
        } else {
            selection = "${MediaStore.Images.Media.DATE_ADDED}>=?"
            args = arrayOf(cutoffSeconds.toString())
        }
        resolver.query(
            uri,
            projection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            return readCursor(cursor, collection, includeDataColumn).take(MAX_RESULTS)
        }
        return emptyList()
    }

    private fun readCursor(
        cursor: Cursor,
        collection: Uri,
        includeDataColumn: Boolean
    ): List<CapturedImage> {
        val images = mutableListOf<CapturedImage>()
        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
        val pathIdx = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        val takenIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
        val addedIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
        val pendingIdx = cursor.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
        val dataIdx = if (includeDataColumn) cursor.getColumnIndex(MediaStore.Images.Media.DATA) else -1
        while (cursor.moveToNext()) {
            if (pendingIdx >= 0 && !cursor.isNull(pendingIdx) && cursor.getInt(pendingIdx) == 1) {
                continue
            }
            val id = cursor.getLong(idIdx)
            val dateTaken = if (takenIdx >= 0 && !cursor.isNull(takenIdx)) cursor.getLong(takenIdx) else 0L
            val dateAddedSec = if (addedIdx >= 0 && !cursor.isNull(addedIdx)) cursor.getLong(addedIdx) else 0L
            val takenAt = if (dateTaken > 0L) dateTaken else dateAddedSec * 1000L
            images += CapturedImage(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                displayName = cursor.stringOrNull(nameIdx),
                relativePath = cursor.stringOrNull(pathIdx),
                dataPath = cursor.stringOrNull(dataIdx),
                takenAtMs = takenAt
            )
        }
        return images
    }

    private fun Cursor.stringOrNull(index: Int): String? {
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    companion object {
        private const val TAG = "ScreenshotReader"
        private const val RECENT_WINDOW_SECONDS = 180L
        private const val MAX_RESULTS = 8

        fun imageIdFrom(uri: Uri?): Long? {
            if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT) return null
            return uri.lastPathSegment?.toLongOrNull()
        }
    }
}
