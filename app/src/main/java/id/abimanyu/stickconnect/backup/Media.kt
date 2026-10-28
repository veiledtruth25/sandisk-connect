package id.abimanyu.stickconnect.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val bucket: String,
    val size: Long,
    val dateModified: Long,
    val isVideo: Boolean,
) {
    val key: String get() = "${if (isVideo) "v" else "i"}:$id:$size"
}

object Media {
    private const val BUCKET = "bucket_display_name"

    private val sources = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
    )

    fun query(ctx: Context, buckets: Set<String>): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            BUCKET,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        for ((collection, isVideo) in sources) {
            try {
                ctx.contentResolver.query(
                    collection, projection, null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iBucket = c.getColumnIndexOrThrow(BUCKET)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (c.moveToNext()) {
                        val bucket = c.getString(iBucket) ?: "Lainnya"
                        if (buckets.isNotEmpty() && bucket !in buckets) continue
                        val size = c.getLong(iSize)
                        if (size <= 0) continue
                        val id = c.getLong(iId)
                        out += MediaItem(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = c.getString(iName) ?: "file_$id",
                            bucket = bucket,
                            size = size,
                            dateModified = c.getLong(iDate),
                            isVideo = isVideo,
                        )
                    }
                }
            } catch (e: SecurityException) {
                // izin belum diberikan
            }
        }
        return out.sortedByDescending { it.dateModified }
    }

    /** Daftar album (bucket) beserta jumlah file. */
    fun buckets(ctx: Context): List<Pair<String, Int>> =
        query(ctx, emptySet()).groupingBy { it.bucket }.eachCount()
            .toList().sortedByDescending { it.second }
}
