package id.abimanyu.stickconnect.update

import id.abimanyu.stickconnect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class UpdateInfo(
    val versionName: String,   // "1.0.42"
    val downloadUrl: String,   // URL download APK dari GitHub
)

object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/veiledtruth25/sandisk-connect/releases/latest"

    /**
     * Cek apakah ada versi baru di GitHub Releases.
     * Return null kalau tidak ada update atau gagal cek (jaringan error, dsb).
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(URL(API).readText())
            val assets = json.getJSONArray("assets")
            if (assets.length() == 0) return@withContext null

            val asset = assets.getJSONObject(0)
            val name = asset.getString("name")          // "StickConnect-1.0.42.apk"
            val url = asset.getString("browser_download_url")

            // Ambil versi dari nama file: "StickConnect-1.0.42.apk" → 42
            val remoteCode = name.removeSuffix(".apk")
                .substringAfterLast('.')
                .toIntOrNull() ?: return@withContext null

            val remoteName = name.removeSuffix(".apk").substringAfter('-')  // "1.0.42"

            if (remoteCode > BuildConfig.VERSION_CODE) UpdateInfo(remoteName, url) else null
        } catch (e: Exception) {
            null  // jaringan error, format berubah, dll — abaikan saja
        }
    }
}
