package id.abimanyu.stickconnect.stick

import java.io.IOException

const val AP_HOST = "172.25.63.1"

class AuthRequiredException : IOException("Stick meminta password")
class StickException(message: String) : IOException(message)

data class HomeStatus(val status: String, val ssid: String, val ip: String) {
    val isConnected: Boolean
        get() = status.equals("connected", true) && ip.isNotEmpty() && ip != "0.0.0.0"
}

data class StickInfo(
    val model: String,
    val ssid: String,
    val hostname: String,
    val version: String,
    val build: Int,
    val serial: String,
    val batteryStatus: String,
    val batteryVoltage: Int,
    val cardStatus: String,
    val cardFormat: String,
    val cardFree: Long,
    val cardTotal: Long,
    val passthrough: Boolean,
    val security: String,
    val timeoutMinutes: Int,
    val client: HomeStatus?,
)

data class WifiNet(
    val ssid: String,
    val security: String,
    val rssi: Int,
    val connected: Boolean,
) {
    /** Nilai "security" yang dikirim ke stick. */
    val securityParam: String
        get() = when (security.lowercase()) {
            "wpa", "wpa2" -> "wpa"
            "wep" -> "wep"
            else -> "none"
        }
    val needsPassword: Boolean get() = securityParam != "none"
}

data class RemoteFile(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: String,
)

object Paths {
    fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }
    fun normalize(path: String): String = "/" + segments(path).joinToString("/")
    fun join(dir: String, name: String): String = normalize("$dir/$name")
    fun parent(path: String): String = normalize(normalize(path).substringBeforeLast('/', ""))
}
