package id.abimanyu.stickconnect.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.abimanyu.stickconnect.stick.AuthRequiredException
import id.abimanyu.stickconnect.stick.Paths
import id.abimanyu.stickconnect.stick.RemoteFile
import id.abimanyu.stickconnect.stick.Stick
import id.abimanyu.stickconnect.stick.StickClient
import id.abimanyu.stickconnect.stick.WifiNet
import kotlinx.coroutines.launch
import java.io.IOException

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    // koneksi
    var conn by mutableStateOf<Stick.Connection?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    var connStatus by mutableStateOf("Mencari stick…")
        private set
    var needPassword by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)

    // file
    var path by mutableStateOf("/")
        private set
    var files by mutableStateOf<List<RemoteFile>>(emptyList())
        private set
    var filesLoading by mutableStateOf(false)
        private set
    var transfer by mutableStateOf<String?>(null)
        private set
    var transferProgress by mutableStateOf(0f)
        private set

    // wifi rumah
    var scanning by mutableStateOf(false)
        private set
    var scanResults by mutableStateOf<List<WifiNet>>(emptyList())
        private set
    var saved by mutableStateOf<List<WifiNet>>(emptyList())
        private set
    var wifiBusy by mutableStateOf<String?>(null)
        private set

    init {
        Stick.init(app)
        connect()
    }

    fun connect() {
        if (connecting) return
        viewModelScope.launch {
            connecting = true
            connStatus = "Mencari stick…"
            try {
                val c = Stick.connect(ctx)
                conn = c
                needPassword = false
                connStatus = if (c.viaHome) "Lewat WiFi rumah • ${c.client.host}" else "Lewat WiFi stick • ${c.client.host}"
                loadDir(path)
                loadSaved()
            } catch (e: AuthRequiredException) {
                conn = null
                needPassword = true
                connStatus = "Stick meminta password"
            } catch (e: Exception) {
                conn = null
                connStatus = e.message ?: "Tidak terhubung"
            } finally {
                connecting = false
            }
        }
    }

    private fun op(label: String? = null, block: suspend (StickClient) -> Unit) {
        val c = conn?.client
        if (c == null) {
            message = "Stick belum terhubung"
            return
        }
        viewModelScope.launch {
            try {
                block(c)
            } catch (e: AuthRequiredException) {
                needPassword = true
            } catch (e: Exception) {
                message = (label?.let { "$it: " } ?: "") + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun refreshInfo() = op("Gagal memuat info") { c ->
        val info = c.info()
        conn = conn?.copy(info = info)
    }

    fun submitPassword(pw: String) {
        Stick.setPassword(ctx, pw)
        needPassword = false
        connect()
    }

    // ---------------- file ----------------

    fun loadDir(p: String) = op("Gagal membuka folder") { c ->
        filesLoading = true
        try {
            files = c.list(p)
            path = Paths.normalize(p)
        } finally {
            filesLoading = false
        }
    }

    fun up() {
        if (path != "/") loadDir(Paths.parent(path))
    }

    fun upload(uris: List<Uri>) = op("Upload gagal") { c ->
        val cr = ctx.contentResolver
        try {
            uris.forEachIndexed { i, uri ->
                val (name, size) = nameAndSize(uri)
                transfer = "Upload ${i + 1}/${uris.size}: $name"
                transferProgress = 0f
                c.upload(Paths.join(path, name), size, {
                    cr.openInputStream(uri) ?: throw IOException("Tidak bisa membuka $name")
                }) { s, t -> transferProgress = if (t > 0) s.toFloat() / t else 0f }
            }
            message = "${uris.size} file ter-upload"
        } finally {
            transfer = null
        }
        files = c.list(path)
    }

    fun download(f: RemoteFile, dest: Uri) = op("Download gagal") { c ->
        try {
            transfer = "Download ${f.name}"
            transferProgress = 0f
            val out = ctx.contentResolver.openOutputStream(dest) ?: throw IOException("Tidak bisa menulis file")
            out.use { o ->
                c.download(f.path, o) { s, t -> transferProgress = if (t > 0) s.toFloat() / t else 0f }
            }
            message = "${f.name} tersimpan"
        } finally {
            transfer = null
        }
    }

    fun mkdir(name: String) = op("Gagal membuat folder") { c ->
        c.mkdir(Paths.join(path, name))
        files = c.list(path)
    }

    fun delete(f: RemoteFile) = op("Gagal menghapus") { c ->
        c.delete(f.path)
        files = c.list(path)
        message = "${f.name} dihapus"
    }

    fun rename(f: RemoteFile, newName: String) = op("Gagal mengganti nama") { c ->
        c.move(f.path, Paths.join(Paths.parent(f.path), newName))
        files = c.list(path)
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = "file"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        if (size < 0) {
            size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }
        if (size < 0) throw IOException("Ukuran $name tidak diketahui")
        return name.replace('/', '_') to size
    }

    // ---------------- wifi rumah ----------------

    fun scan() = op("Scan gagal") { c ->
        scanning = true
        try {
            scanResults = c.scan()
        } finally {
            scanning = false
        }
    }

    fun loadSaved() = op { c -> saved = c.savedNetworks() }

    fun join(net: WifiNet, password: String?) = op("Gagal menyambung") { c ->
        try {
            wifiBusy = "Menyimpan ${net.ssid}…"
            c.saveNetwork(net.ssid, password, net.securityParam)
            wifiBusy = "Menyambungkan stick ke ${net.ssid}…"
            val st = c.connectNetwork(net.ssid) { wifiBusy = it }
            Stick.rememberHomeIp(ctx, st.ip)
            message = "Stick tersambung ke ${st.ssid} (${st.ip})"
            conn = conn?.copy(info = c.info())
            saved = c.savedNetworks()
        } finally {
            wifiBusy = null
        }
    }

    fun connectSaved(net: WifiNet) = op("Gagal menyambung") { c ->
        try {
            wifiBusy = "Menyambungkan stick ke ${net.ssid}…"
            val st = c.connectNetwork(net.ssid) { wifiBusy = it }
            Stick.rememberHomeIp(ctx, st.ip)
            message = "Stick tersambung ke ${st.ssid} (${st.ip})"
            conn = conn?.copy(info = c.info())
        } finally {
            wifiBusy = null
        }
    }

    fun forget(net: WifiNet) = op("Gagal menghapus") { c ->
        c.forgetNetwork(net.ssid)
        saved = c.savedNetworks()
    }

    fun setPassthrough(enable: Boolean) = op("Gagal") { c ->
        c.setPassthrough(enable)
        conn = conn?.copy(info = c.info())
    }

    // ---------------- pengaturan stick ----------------

    fun changeStickPassword(newPw: String) = op("Gagal") { c ->
        val ssid = conn?.info?.ssid ?: return@op
        c.changeStickPassword(ssid, newPw)
        Stick.setPassword(ctx, newPw)
        message = "Password diganti. Stick akan restart — sambungkan ulang WiFi stick dengan password baru, lalu refresh."
    }

    fun renameStick(newSsid: String) = op("Gagal") { c ->
        val pw = Stick.password(ctx)
        if (pw == null) {
            message = "Isi dulu password stick di aplikasi"
            return@op
        }
        c.renameStick(newSsid, pw)
        message = "Nama WiFi stick diganti menjadi $newSsid. Stick akan restart."
    }

    fun setTimeout(minutes: Int) = op("Gagal") { c ->
        c.setTimeout(minutes)
        conn = conn?.copy(info = c.info())
        message = "Auto-mati diatur ke $minutes menit"
    }
}
