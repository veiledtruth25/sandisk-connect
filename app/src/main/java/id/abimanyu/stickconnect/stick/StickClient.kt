package id.abimanyu.stickconnect.stick

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Klien untuk SanDisk Connect Wireless Stick.
 *  - Pengaturan: GET/POST /settings.xml
 *  - File: WebDAV di /files/...
 */
class StickClient(
    val host: String,
    auth: DigestAuth,
    socketFactory: SocketFactory,
    connectTimeoutSec: Long = 8,
) {
    private val base: HttpUrl = "http://$host/".toHttpUrl()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .socketFactory(socketFactory)
        .addInterceptor(DateHeader)
        .addInterceptor(auth)
        .build()

    // ---------------- low level ----------------

    private fun call(req: Request): Response {
        val r = http.newCall(req).execute()
        if (r.code == 401) {
            r.close()
            throw AuthRequiredException()
        }
        return r
    }

    private suspend fun text(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        call(req).use { it.code to (it.body?.string() ?: "") }
    }

    private suspend fun code(req: Request): Int = withContext(Dispatchers.IO) {
        call(req).use { it.code }
    }

    private fun settingsUrl(group: String?): HttpUrl = base.newBuilder()
        .addPathSegment("settings.xml")
        .apply { if (group != null) addQueryParameter("group", group) }
        .build()

    suspend fun getSettings(group: String? = null): String {
        val (c, body) = text(Request.Builder().url(settingsUrl(group)).build())
        if (c !in 200..299) throw StickException("Stick membalas HTTP $c")
        return body.trim()
    }

    suspend fun post(vararg fields: Pair<String, Any>): String {
        val body = Codec.formEncode(fields.toList())
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val (c, resp) = text(Request.Builder().url(settingsUrl(null)).post(body).build())
        if (c !in 200..299) throw StickException("Stick membalas HTTP $c")
        return resp.trim()
    }

    // ---------------- info & pengaturan ----------------

    suspend fun info(): StickInfo = StickXml.parseInfo(getSettings())

    suspend fun setTimeout(minutes: Int) = expectOk(post("timeout" to minutes.toString()), "Gagal mengubah timeout")

    /** Ganti password WiFi stick (sekaligus password akses dari WiFi rumah). Stick akan restart. */
    suspend fun changeStickPassword(stickSsid: String, newPassword: String) {
        require(newPassword.length in 8..63) { "Password harus 8–63 karakter" }
        expectOk(
            post(
                "security" to "wpa",
                "password" to Codec.psk(newPassword, stickSsid),
                "auth" to "all",
                "authowner" to "owner",
                "authhash" to Codec.md5Hex("owner:$stickSsid:$newPassword"),
                "restart" to "allowed",
            ),
            "Gagal mengganti password",
        )
    }

    /** Ganti nama WiFi stick. Butuh password saat ini karena PSK & auth dihitung ulang dari SSID. */
    suspend fun renameStick(newSsid: String, password: String) {
        require(newSsid.isNotBlank() && newSsid.toByteArray().size <= 32) { "Nama WiFi maksimal 32 karakter" }
        expectOk(
            post(
                "ssid" to newSsid,
                "security" to "wpa",
                "password" to Codec.psk(password, newSsid),
                "auth" to "all",
                "authowner" to "owner",
                "authhash" to Codec.md5Hex("owner:$newSsid:$password"),
                "restart" to "allowed",
            ),
            "Gagal mengganti nama",
        )
    }

    // ---------------- WiFi rumah ----------------

    suspend fun scan(): List<WifiNet> {
        expectOk(post("group" to "scan"), "Scan gagal dimulai")
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            delay(4_000)
            val x = getSettings("scan")
            if (x == SCANNING || x == LOCKED) continue
            if (x == NONE) {
                post("group" to "scan")
                continue
            }
            return StickXml.parseNetworks(x)
        }
        throw StickException("Scan timeout")
    }

    suspend fun savedNetworks(): List<WifiNet> = StickXml.parseNetworks(getSettings("saved"))

    suspend fun saveNetwork(ssid: String, password: String?, security: String) {
        val f = mutableListOf<Pair<String, Any>>(
            "group" to "saved", "action" to "save", "ssid" to ssid, "security" to security,
        )
        when (security) {
            "wpa" -> {
                require(password != null && password.length in 8..63) { "Password WiFi harus 8–63 karakter" }
                f += "password" to Codec.psk(password, ssid)
            }
            "wep" -> f += "password" to Codec.hexToBytes(password.orEmpty())
        }
        val r = post(*f.toTypedArray())
        when (r) {
            OK, DUPLICATE -> Unit
            FULL -> throw StickException("Daftar WiFi tersimpan di stick penuh. Hapus salah satu dulu.")
            else -> throw StickException("Gagal menyimpan WiFi: $r")
        }
    }

    suspend fun connectNetwork(ssid: String, onProgress: (String) -> Unit): HomeStatus {
        val r = post("group" to "saved", "action" to "connect", "ssid" to ssid)
        if (r == NOT_FOUND) throw StickException("WiFi belum tersimpan di stick")
        expectOk(r, "Gagal mulai menyambung")
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            delay(2_500)
            val st = try {
                info().client
            } catch (e: AuthRequiredException) {
                throw e
            } catch (e: Exception) {
                onProgress("WiFi stick sedang pindah channel, menunggu…")
                continue
            }
            if (st != null && st.isConnected) return st
            if (st?.status.equals("failed", true)) {
                throw StickException("Stick gagal tersambung ke $ssid. Cek password atau sinyal.")
            }
            onProgress("Status: ${st?.status?.ifEmpty { null } ?: "menunggu"}…")
        }
        throw StickException("Timeout. Sambungkan ulang HP ke WiFi stick atau WiFi rumah, lalu refresh.")
    }

    suspend fun forgetNetwork(ssid: String) {
        val r = post("group" to "saved", "action" to "delete", "ssid" to ssid)
        if (r != OK && r != NOT_FOUND) throw StickException("Gagal menghapus: $r")
    }

    suspend fun setPassthrough(enable: Boolean) =
        expectOk(post("sidelinken" to enable.toString()), "Gagal mengubah pass-through")

    private fun expectOk(r: String, what: String) {
        if (r != OK && r != OK_RESTART) throw StickException("$what ($r)")
    }

    // ---------------- File (WebDAV) ----------------

    private fun fileUrl(path: String, dir: Boolean = false): HttpUrl = base.newBuilder()
        .addPathSegment("files")
        .apply {
            Paths.segments(path).forEach { addPathSegment(it) }
            if (dir) addPathSegment("")
        }
        .build()

    suspend fun list(path: String): List<RemoteFile> {
        val req = Request.Builder()
            .url(fileUrl(path, dir = true))
            .method("PROPFIND", PROPFIND_XML.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        val (c, body) = text(req)
        if (c != 207) throw StickException("Gagal membaca folder (HTTP $c)")
        return StickXml.parsePropfind(body, path)
    }

    /** Ukuran file di stick, atau null kalau tidak ada. */
    suspend fun stat(path: String): Long? = withContext(Dispatchers.IO) {
        call(Request.Builder().url(fileUrl(path)).head().build()).use { r ->
            when {
                r.code == 404 -> null
                r.code in 200..299 -> r.header("Content-Length")?.toLongOrNull() ?: 0L
                else -> null
            }
        }
    }

    suspend fun download(path: String, out: OutputStream, progress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            call(Request.Builder().url(fileUrl(path)).get().build()).use { r ->
                if (!r.isSuccessful) throw StickException("Download gagal (HTTP ${r.code})")
                val body = r.body ?: throw StickException("Respons kosong")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            progress(done, total)
                            lastReport = done
                        }
                    }
                    progress(done, total)
                }
            }
        }

    suspend fun upload(path: String, size: Long, open: () -> InputStream, progress: (Long, Long) -> Unit) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(128 * 1024)
                    var sent = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        if (sent - lastReport > 256 * 1024) {
                            progress(sent, size)
                            lastReport = sent
                        }
                    }
                    progress(sent, size)
                }
            }
        }
        val c = code(Request.Builder().url(fileUrl(path)).put(body).build())
        when (c) {
            200, 201, 204 -> Unit
            507 -> throw StickException("Stick penuh")
            else -> throw StickException("Upload gagal (HTTP $c)")
        }
    }

    suspend fun mkdir(path: String) {
        val c = code(Request.Builder().url(fileUrl(path)).method("MKCOL", null).build())
        if (c != 200 && c != 201 && c != 405) throw StickException("Gagal membuat folder (HTTP $c)")
    }

    suspend fun mkdirs(path: String) {
        var cur = ""
        for (seg in Paths.segments(path)) {
            cur += "/$seg"
            mkdir(cur)
        }
    }

    suspend fun delete(path: String) {
        val c = code(Request.Builder().url(fileUrl(path)).delete().build())
        if (c !in listOf(200, 202, 204, 404)) throw StickException("Gagal menghapus (HTTP $c)")
    }

    suspend fun move(from: String, to: String) {
        val req = Request.Builder()
            .url(fileUrl(from))
            .method("MOVE", null)
            .header("Destination", fileUrl(to).toString())
            .header("Overwrite", "F")
            .build()
        val c = code(req)
        if (c != 201 && c != 204 && c != 200) throw StickException("Gagal memindahkan (HTTP $c)")
    }

    suspend fun copy(from: String, to: String) {
        val req = Request.Builder()
            .url(fileUrl(from))
            .method("COPY", null)
            .header("Destination", fileUrl(to).toString())
            .header("Overwrite", "F")
            .build()
        val c = code(req)
        if (c != 201 && c != 204 && c != 200) throw StickException("Gagal menyalin (HTTP $c)")
    }

    private object DateHeader : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).format(Date())
            return chain.proceed(chain.request().newBuilder().header("X-Airstash-Date", date).build())
        }
    }

    companion object {
        const val OK = "<status>ok</status>"
        const val OK_RESTART = "<status restart=\"pending\">ok</status>"
        const val DUPLICATE = "<status>duplicate</status>"
        const val FULL = "<status>full</status>"
        const val NOT_FOUND = "<status>notfound</status>"
        const val LOCKED = "<status>locked</status>"
        const val SCANNING = "<status>scanning</status>"
        const val NONE = "<status>none</status>"

        private const val PROPFIND_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:prop>" +
                "<D:displayname/><D:getcontentlength/><D:getcontenttype/><D:resourcetype/>" +
                "<D:getlastmodified/><D:creationdate/></D:prop></D:propfind>"
    }
}
