package id.abimanyu.stickconnect.stick

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

object StickXml {
    private const val DAV = "DAV:"

    private fun parse(xml: String, namespaces: Boolean): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = namespaces
        return f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    /** SSID dari stick: percent-encoded byte UTF-8. '+' dibiarkan apa adanya. */
    fun decodeSsid(s: String): String = try {
        val latin = URLDecoder.decode(s.replace("+", "%2B"), "ISO-8859-1")
        String(latin.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    } catch (e: Exception) {
        s
    }

    private fun Element.child(tag: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.nodeName == tag) return n
        }
        return null
    }

    private fun Element.childText(tag: String): String = child(tag)?.textContent?.trim().orEmpty()

    fun parseInfo(xml: String): StickInfo {
        val root = parse(xml, false).documentElement
        val card = root.child("cards")?.getElementsByTagName("card")?.item(0) as? Element
        val blockSize = card?.getAttribute("blocksize")?.toLongOrNull() ?: 512L
        val clientEl = root.getElementsByTagName("client").item(0) as? Element
        val battery = root.child("battery")
        val client = clientEl?.let {
            HomeStatus(it.getAttribute("status"), decodeSsid(it.getAttribute("ssid")), it.getAttribute("ip"))
        }
        return StickInfo(
            model = root.childText("model"),
            ssid = root.childText("ssid"),
            hostname = root.childText("hostname"),
            version = root.childText("version"),
            build = root.childText("numericversion").toIntOrNull() ?: 0,
            serial = root.childText("serial"),
            batteryStatus = battery?.getAttribute("status").orEmpty(),
            batteryVoltage = battery?.getAttribute("voltage")?.toIntOrNull() ?: 0,
            cardStatus = card?.getAttribute("status").orEmpty(),
            cardFormat = card?.getAttribute("format").orEmpty(),
            cardFree = (card?.getAttribute("free")?.toLongOrNull() ?: 0L) * blockSize,
            cardTotal = (card?.getAttribute("total")?.toLongOrNull() ?: 0L) * blockSize,
            passthrough = root.child("sidelink")?.getAttribute("enabled").equals("true", true),
            security = root.childText("security"),
            timeoutMinutes = root.childText("timeout").toIntOrNull() ?: 0,
            client = client,
        )
    }

    fun parseNetworks(xml: String): List<WifiNet> {
        if (xml.trim().startsWith("<status>")) return emptyList()
        val nodes = parse(xml, false).getElementsByTagName("ssid")
        val out = mutableListOf<WifiNet>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = decodeSsid(e.getAttribute("name"))
            if (name.isEmpty() || name[0] == '\u0000') continue
            out += WifiNet(
                ssid = name,
                security = e.getAttribute("security").ifEmpty { "none" },
                rssi = e.getAttribute("rssi").toIntOrNull() ?: 0,
                connected = e.getAttribute("connected").equals("connected", true),
            )
        }
        return out.distinctBy { it.ssid }.sortedByDescending { it.rssi }
    }

    fun parsePropfind(xml: String, selfPath: String): List<RemoteFile> {
        val doc = parse(xml, true)
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        val self = Paths.normalize(selfPath)
        val out = mutableListOf<RemoteFile>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as? Element ?: continue
            val href = r.getElementsByTagNameNS(DAV, "href").item(0)?.textContent?.trim() ?: continue
            var p = if (href.contains("://")) "/" + href.substringAfter("://").substringAfter('/', "") else href
            p = try {
                URLDecoder.decode(p.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                p
            }
            p = p.trim()
            if (p.startsWith("/files")) p = p.removePrefix("/files")
            p = Paths.normalize(p)
            if (p == self) continue
            val isDir = r.getElementsByTagNameNS(DAV, "collection").length > 0
            val size = r.getElementsByTagNameNS(DAV, "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
            val mod = r.getElementsByTagNameNS(DAV, "getlastmodified").item(0)?.textContent?.trim().orEmpty()
            out += RemoteFile(p, p.substringAfterLast('/'), isDir, size, mod)
        }
        return out.distinctBy { it.path }.sortedWith(compareBy<RemoteFile>({ !it.isDir }, { it.name.lowercase() }))
    }
}
