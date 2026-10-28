package id.abimanyu.stickconnect.stick

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume

/** Mencari stick (WiFi rumah atau WiFi stick) dan menyimpan pengaturan koneksi. */
object Stick {
    private const val PREFS = "stick"
    private const val SERVICE_TYPE = "_airstash._tcp."

    @Volatile
    private var appContext: Context? = null

    val auth = DigestAuth("owner") { appContext?.let { prefs(it).getString("password", null) } }

    data class Connection(val client: StickClient, val info: StickInfo, val viaHome: Boolean)

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun password(ctx: Context): String? = prefs(ctx).getString("password", null)

    fun setPassword(ctx: Context, pw: String?) {
        prefs(ctx).edit().putString("password", pw?.ifEmpty { null }).apply()
        auth.clear()
    }

    fun lastHomeIp(ctx: Context): String? = prefs(ctx).getString("home_ip", null)

    fun rememberHomeIp(ctx: Context, ip: String) {
        if (ip.isNotEmpty() && ip != "0.0.0.0") prefs(ctx).edit().putString("home_ip", ip).apply()
    }

    /** OkHttpClient siap pakai untuk Coil (gambar) dan ExoPlayer (video). */
    fun mediaOkHttp(ctx: Context): OkHttpClient {
        init(ctx)
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .socketFactory(WifiSocketFactory(ctx.applicationContext))
            .addInterceptor(auth)
            .build()
    }

    suspend fun connect(ctx: Context): Connection {
        init(ctx)
        val sf = WifiSocketFactory(ctx.applicationContext)
        val tried = mutableSetOf<String>()
        var authError: AuthRequiredException? = null

        suspend fun attempt(host: String): Connection? {
            if (!tried.add(host)) return null
            return try {
                val probe = StickClient(host, auth, sf, connectTimeoutSec = 3)
                val info = withTimeout(8_000) { probe.info() }
                if (host == AP_HOST) {
                    info.client?.takeIf { it.isConnected }?.let { rememberHomeIp(ctx, it.ip) }
                } else {
                    rememberHomeIp(ctx, host)
                }
                Connection(StickClient(host, auth, sf), info, viaHome = host != AP_HOST)
            } catch (e: AuthRequiredException) {
                authError = e
                null
            } catch (e: Exception) {
                null
            }
        }

        lastHomeIp(ctx)?.let { ip -> attempt(ip)?.let { return it } }
        attempt(AP_HOST)?.let { return it }
        discover(ctx, 6_000)?.let { h -> attempt(h)?.let { return it } }
        authError?.let { throw it }
        throw StickException(
            "Stick tidak ditemukan. Pastikan stick menyala dan HP tersambung ke WiFi rumah atau WiFi stick."
        )
    }

    /** Cari stick di jaringan rumah lewat Bonjour (_airstash._tcp). */
    @Suppress("DEPRECATION")
    private suspend fun discover(ctx: Context, timeoutMs: Long): String? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        var listener: NsdManager.DiscoveryListener? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    val l = object : NsdManager.DiscoveryListener {
                        override fun onServiceFound(service: NsdServiceInfo) {
                            nsd.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {}
                                override fun onServiceResolved(s: NsdServiceInfo) {
                                    val addr = s.host
                                    if (addr is Inet4Address && cont.isActive) {
                                        val host = if (s.port == 80 || s.port <= 0) addr.hostAddress else "${addr.hostAddress}:${s.port}"
                                        runCatching { cont.resume(host) }
                                    }
                                }
                            })
                        }

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (cont.isActive) runCatching { cont.resume(null) }
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onServiceLost(service: NsdServiceInfo) {}
                    }
                    listener = l
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
                }
            }
        } catch (e: Exception) {
            return null
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    /** Jaringan WiFi aktif (dipakai walau WiFi stick tidak punya internet). */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}

/**
 * Membuat socket lewat jaringan WiFi yang aktif saat itu. Android sering mengarahkan
 * trafik ke data seluler kalau WiFi (seperti WiFi stick) tidak punya internet.
 */
class WifiSocketFactory(private val ctx: Context) : SocketFactory() {
    private fun delegate(): SocketFactory = Stick.wifiNetwork(ctx)?.socketFactory ?: getDefault()

    override fun createSocket(): Socket = delegate().createSocket()
    override fun createSocket(host: String?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate().createSocket(host, port)
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate().createSocket(address, port, localAddress, localPort)
}
