package id.abimanyu.stickconnect.stick

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Encoding yang dipakai firmware stick (diambil dari APK resmi Connect Drive 2.1.1). */
object Codec {

    /** WPA PSK = PBKDF2-HMAC-SHA1(password, ssid, 4096 iterasi, 32 byte). */
    fun psk(password: String, ssid: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.ISO_8859_1), "HmacSHA1"))
        val salt = ssid.toByteArray(Charsets.UTF_8)
        val out = ByteArray(32)
        var offset = 0
        var block = 1
        while (offset < out.size) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(4095) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, out.size - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Kunci WEP harus berupa hex" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * application/x-www-form-urlencoded seperti UrlEncodedFormEntity (ISO-8859-1):
     * nilai String dikirim sebagai byte UTF-8, nilai ByteArray dikirim mentah.
     */
    fun formEncode(fields: List<Pair<String, Any>>): ByteArray {
        val sb = StringBuilder()
        fields.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append('&')
            encode(k.toByteArray(Charsets.UTF_8), sb)
            sb.append('=')
            val bytes = when (v) {
                is ByteArray -> v
                is String -> v.toByteArray(Charsets.UTF_8)
                else -> v.toString().toByteArray(Charsets.UTF_8)
            }
            encode(bytes, sb)
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun encode(bytes: ByteArray, sb: StringBuilder) {
        for (b in bytes) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '*' || ch == '_' -> sb.append(ch)
                ch == ' ' -> sb.append('+')
                else -> sb.append('%').append("%02X".format(c))
            }
        }
    }
}
