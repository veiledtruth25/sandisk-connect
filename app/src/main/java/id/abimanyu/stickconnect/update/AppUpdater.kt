package id.abimanyu.stickconnect.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import id.abimanyu.stickconnect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    /**
     * Download APK ke cache lalu buka dialog installer sistem Android.
     * [onProgress] dipanggil dengan nilai 0–100 saat download berlangsung.
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "apk").also { it.mkdirs() }
        val apk = File(dir, "update.apk")

        // Download dengan follow redirect (GitHub redirect ke S3)
        var downloadUrl = info.downloadUrl
        repeat(5) {
            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connect()
            if (conn.responseCode in 301..308) {
                downloadUrl = conn.getHeaderField("Location") ?: downloadUrl
                conn.disconnect()
            }
        }

        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connect()
        val total = conn.contentLength.toLong()

        conn.inputStream.use { input ->
            apk.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                var downloaded = 0L
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) onProgress((downloaded * 100 / total).toInt())
                }
            }
        }
        onProgress(100)

        val uri = FileProvider.getUriForFile(
            ctx,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
