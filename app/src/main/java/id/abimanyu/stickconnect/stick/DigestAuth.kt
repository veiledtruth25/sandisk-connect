package id.abimanyu.stickconnect.stick

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP Digest auth (user "owner"). Challenge disimpan supaya request berikutnya
 * (termasuk upload besar) langsung membawa header Authorization tanpa ditolak dulu.
 */
class DigestAuth(
    private val user: String = "owner",
    private val password: () -> String?,
) : Interceptor {

    @Volatile
    private var challenge: Map<String, String>? = null
    private val nc = AtomicInteger(0)
    private val random = SecureRandom()

    fun clear() {
        challenge = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val pw = password()
        val ch = challenge
        val first = if (pw != null && ch != null) {
            req.newBuilder().header("Authorization", header(req, ch, pw)).build()
        } else req

        val resp = chain.proceed(first)
        if (resp.code != 401 || pw == null) return resp
        val www = resp.headers("WWW-Authenticate").firstOrNull { it.startsWith("Digest", ignoreCase = true) }
            ?: return resp
        val fresh = parse(www)
        challenge = fresh
        nc.set(0)
        resp.close()
        return chain.proceed(req.newBuilder().header("Authorization", header(req, fresh, pw)).build())
    }

    private fun header(req: Request, ch: Map<String, String>, pw: String): String {
        val realm = ch["realm"].orEmpty()
        val nonce = ch["nonce"].orEmpty()
        val qop = ch["qop"]
        val opaque = ch["opaque"]
        val uri = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        val ha1 = Codec.md5Hex("$user:$realm:$pw")
        val ha2 = Codec.md5Hex("${req.method}:$uri")
        val sb = StringBuilder()
        sb.append("Digest username=\"").append(user)
            .append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce)
            .append("\", uri=\"").append(uri).append('"')
        val qops = qop?.split(',')?.map { it.trim() } ?: emptyList()
        if ("auth" in qops) {
            val count = String.format(Locale.US, "%08x", nc.incrementAndGet())
            val cnonce = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val response = Codec.md5Hex("$ha1:$nonce:$count:$cnonce:auth:$ha2")
            sb.append(", qop=auth, nc=").append(count)
                .append(", cnonce=\"").append(cnonce)
                .append("\", response=\"").append(response).append('"')
        } else {
            sb.append(", response=\"").append(Codec.md5Hex("$ha1:$nonce:$ha2")).append('"')
        }
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append('"')
        ch["algorithm"]?.let { sb.append(", algorithm=").append(it) }
        return sb.toString()
    }

    private fun parse(h: String): Map<String, String> {
        val body = h.substringAfter(' ', "")
        return Regex("""(\w+)=(?:"([^"]*)"|([^\s,]+))""").findAll(body).associate { m ->
            val quoted = m.groups[2]?.value
            m.groupValues[1].lowercase(Locale.US) to (quoted ?: m.groupValues[3])
        }
    }
}
