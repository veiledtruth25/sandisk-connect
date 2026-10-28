#!/usr/bin/env python3
"""
sandisk_stick.py - klien uji untuk SanDisk Connect Wireless Stick.

Protokol diambil dari APK resmi "Connect Drive" 2.1.1 (com.wearable.sdk):
  - Pengaturan : GET/POST  http://<stick>/settings.xml   (form url-encoded, balasan XML)
  - File       : WebDAV di http://<stick>/files/...  (PROPFIND, GET, PUT, MKCOL, DELETE, MOVE)
  - Auth       : HTTP Digest, user "owner" (hanya kalau stick diberi password)

Pakai dari laptop yang sedang tersambung ke WiFi stick:
  python3 sandisk_stick.py info
  python3 sandisk_stick.py scan
  python3 sandisk_stick.py join "NamaWiFiRumah" "passwordwifi"
  python3 sandisk_stick.py status
  python3 sandisk_stick.py ls /
  python3 sandisk_stick.py put foto.jpg /DCIM/foto.jpg
  python3 sandisk_stick.py get /DCIM/foto.jpg ./foto.jpg

Butuh: python3-requests  (sudo apt install python3-requests)
"""
import argparse
import hashlib
import os
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from urllib.parse import quote, unquote, urlparse

try:
    import requests
    from requests.auth import HTTPDigestAuth
except ImportError:
    sys.exit("Butuh modul requests: sudo apt install python3-requests")

OK = "<status>ok</status>"
OK_RESTART = '<status restart="pending">ok</status>'
PROPFIND_XML = (
    '<?xml version="1.0" encoding="utf-8" ?><D:propfind xmlns:D="DAV:"><D:prop>'
    "<D:displayname/><D:getcontentlength/><D:getcontenttype/><D:resourcetype/>"
    "<D:getlastmodified/><D:creationdate/></D:prop></D:propfind>"
)


class Stick:
    def __init__(self, host, port, password=None, verbose=False):
        self.base = f"http://{host}:{port}"
        self.s = requests.Session()
        self.verbose = verbose
        if password:
            self.s.auth = HTTPDigestAuth("owner", password)

    # ---------- low level ----------
    def _headers(self, extra=None):
        now = datetime.now().astimezone()
        h = {"X-Airstash-Date": now.strftime("%Y-%m-%d %H:%M:%S") + now.strftime("%z")}
        if extra:
            h.update(extra)
        return h

    def req(self, method, path, timeout=30, **kw):
        url = self.base + path
        headers = self._headers(kw.pop("headers", None))
        r = self.s.request(method, url, headers=headers, timeout=timeout, **kw)
        if self.verbose:
            print(f"[{method} {url}] -> {r.status_code}", file=sys.stderr)
        if r.status_code == 401:
            sys.exit("401 Unauthorized: stick memakai password, jalankan ulang dengan --password")
        return r

    def get_settings(self, group=None):
        path = "/settings.xml" + (f"?group={group}" if group else "")
        return self.req("GET", path).text.strip()

    def post_settings(self, data):
        r = self.req("POST", "/settings.xml", data=data)
        return r.text.strip()

    # ---------- info ----------
    def info(self):
        return self.get_settings()

    # ---------- home network / pass-through ----------
    def scan(self, timeout=30):
        res = self.post_settings({"group": "scan"})
        if res != OK:
            raise RuntimeError(f"Gagal mulai scan: {res}")
        t0 = time.time()
        while time.time() - t0 < timeout:
            time.sleep(4)
            xml = self.get_settings("scan")
            if xml in ("<status>scanning</status>", "<status>locked</status>"):
                continue
            if xml == "<status>none</status>":
                self.post_settings({"group": "scan"})
                continue
            return parse_networks(xml)
        raise TimeoutError("Scan timeout")

    def saved(self):
        return parse_networks(self.get_settings("saved"), raw=True)

    def save_network(self, ssid, password=None, security=None):
        if security is None:
            security = "wpa" if password else "none"
        data = {"group": "saved", "action": "save", "ssid": enc_ssid(ssid), "security": security}
        if security == "wpa":
            if not (8 <= len(password) <= 63):
                raise ValueError("Password WPA harus 8-63 karakter")
            # Sama seperti app resmi: kirim PSK hasil PBKDF2 (32 byte mentah), bukan password asli
            data["password"] = hashlib.pbkdf2_hmac(
                "sha1", password.encode("latin-1"), ssid.encode("utf-8"), 4096, 32
            )
        elif security == "wep":
            data["password"] = bytes.fromhex(password)
        res = self.post_settings(data)
        if res not in (OK, "<status>duplicate</status>"):
            raise RuntimeError(f"Gagal simpan jaringan: {res}")
        return res

    def connect_network(self, ssid, timeout=120):
        res = self.post_settings({"group": "saved", "action": "connect", "ssid": enc_ssid(ssid)})
        if res != OK:
            raise RuntimeError(f"Gagal mulai koneksi: {res}")
        t0 = time.time()
        while time.time() - t0 < timeout:
            time.sleep(2)
            try:
                st = self.status()
            except (requests.exceptions.ConnectionError, requests.exceptions.Timeout):
                # Saat menyambung ke WiFi rumah, radio stick pindah ke channel router,
                # jadi WiFi "SanDisk Connect" hilang sebentar. Tunggu laptop/HP tersambung lagi.
                print("  ... WiFi stick sedang pindah channel, menunggu tersambung lagi", file=sys.stderr)
                continue
            if st and st["status"] == "connected" and st["ip"] not in ("", "0.0.0.0"):
                return st
            if st and st["status"] == "failed":
                raise RuntimeError("Stick gagal tersambung (password salah / sinyal lemah?)")
            print(f"  ... {st['status'] if st else 'menunggu'}", file=sys.stderr)
        raise TimeoutError("Timeout menunggu koneksi ke WiFi rumah")

    def forget_network(self, ssid):
        return self.post_settings({"group": "saved", "action": "delete", "ssid": enc_ssid(ssid)})

    def set_passthrough(self, enable):
        return self.post_settings({"sidelinken": "true" if enable else "false"})

    def status(self):
        root = ET.fromstring(self.get_settings())
        sl = root.find(".//sidelink")
        cl = root.find(".//client")
        out = {"passthrough_enabled": sl.get("enabled") if sl is not None else None}
        if cl is None:
            return {**out, "status": "none", "ssid": "", "ip": ""}
        return {
            **out,
            "status": cl.get("status", ""),
            "ssid": unquote(cl.get("ssid", ""), encoding="latin-1"),
            "ip": cl.get("ip", ""),
        }

    # ---------- files (WebDAV) ----------
    def _fpath(self, p):
        p = "/" + p.lstrip("/")
        return "/files" + quote(p)

    def ls(self, path="/"):
        p = self._fpath(path)
        if not p.endswith("/"):
            p += "/"
        r = self.req("PROPFIND", p, data=PROPFIND_XML,
                     headers={"Depth": "1", "Content-Type": "text/xml; charset=utf-8"})
        if r.status_code != 207:
            raise RuntimeError(f"PROPFIND gagal: HTTP {r.status_code}")
        ns = {"D": "DAV:"}
        items = []
        self_path = unquote(p).rstrip("/")
        for resp in ET.fromstring(r.content).findall("D:response", ns):
            href = unquote(resp.findtext("D:href", "", ns)).strip()
            if urlparse(href).path.rstrip("/") == self_path:
                continue  # entri folder itu sendiri
            prop = resp.find(".//D:prop", ns)
            is_dir = prop is not None and prop.find("D:resourcetype/D:collection", ns) is not None
            size = prop.findtext("D:getcontentlength", "", ns) if prop is not None else ""
            mod = prop.findtext("D:getlastmodified", "", ns) if prop is not None else ""
            items.append((href, is_dir, size, mod))
        return items

    def get(self, remote, local):
        with self.req("GET", self._fpath(remote), stream=True, timeout=120) as r:
            if r.status_code != 200:
                raise RuntimeError(f"Download gagal: HTTP {r.status_code}")
            with open(local, "wb") as f:
                for chunk in r.iter_content(256 * 1024):
                    f.write(chunk)

    def put(self, local, remote):
        with open(local, "rb") as f:
            r = self.req("PUT", self._fpath(remote), data=f, timeout=600)
        if r.status_code not in (200, 201, 204):
            raise RuntimeError(f"Upload gagal: HTTP {r.status_code} {r.text[:200]}")

    def mkdir(self, path):
        r = self.req("MKCOL", self._fpath(path))
        if r.status_code not in (200, 201):
            raise RuntimeError(f"MKCOL gagal: HTTP {r.status_code}")

    def rm(self, path):
        r = self.req("DELETE", self._fpath(path))
        if r.status_code not in (200, 202, 204):
            raise RuntimeError(f"DELETE gagal: HTTP {r.status_code}")


def enc_ssid(ssid):
    # App resmi: new String(ssid.getBytes("UTF-8"), "ISO-8859-15") -> kirim byte UTF-8 apa adanya
    return ssid.encode("utf-8")


def parse_networks(xml, raw=False):
    if xml.startswith("<status>"):
        return []
    nets = []
    for el in ET.fromstring(xml).iter("ssid"):
        name = unquote(el.get("name", ""), encoding="latin-1")
        if not name:
            continue
        nets.append({
            "ssid": name,
            "security": el.get("security", "none"),
            "rssi": int(el.get("rssi", "0") or 0),
            "connected": el.get("connected", ""),
        })
    nets.sort(key=lambda n: n["rssi"], reverse=True)
    return nets


def main():
    ap = argparse.ArgumentParser(description="Klien uji SanDisk Connect Wireless Stick")
    ap.add_argument("--host", default="172.25.63.1")
    ap.add_argument("--port", type=int, default=80)
    ap.add_argument("--password", help="password stick (kalau diset)")
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("info", help="tampilkan settings.xml mentah")
    sub.add_parser("status", help="status koneksi ke WiFi rumah")
    sub.add_parser("scan", help="scan WiFi di sekitar stick")
    sub.add_parser("saved", help="daftar WiFi rumah yang tersimpan")
    j = sub.add_parser("join", help="simpan + sambungkan stick ke WiFi rumah")
    j.add_argument("ssid"); j.add_argument("wifi_password", nargs="?")
    f = sub.add_parser("forget", help="hapus WiFi rumah tersimpan"); f.add_argument("ssid")
    p = sub.add_parser("passthrough", help="nyalakan/matikan mode pass-through")
    p.add_argument("mode", choices=["on", "off"])
    l = sub.add_parser("ls"); l.add_argument("path", nargs="?", default="/")
    g = sub.add_parser("get"); g.add_argument("remote"); g.add_argument("local", nargs="?")
    u = sub.add_parser("put"); u.add_argument("local"); u.add_argument("remote", nargs="?")
    m = sub.add_parser("mkdir"); m.add_argument("path")
    r = sub.add_parser("rm"); r.add_argument("path")
    a = ap.parse_args()

    st = Stick(a.host, a.port, a.password, a.verbose)
    if a.cmd == "info":
        print(st.info())
    elif a.cmd == "status":
        print(st.status())
    elif a.cmd == "scan":
        for n in st.scan():
            print(f"{n['rssi']:>4}  {n['security']:<5} {n['ssid']} {n['connected']}")
    elif a.cmd == "saved":
        for n in st.saved():
            print(f"{n['security']:<5} {n['ssid']} {n['connected']}")
    elif a.cmd == "join":
        sec = None
        try:
            for n in st.scan():
                if n["ssid"] == a.ssid:
                    sec = "wpa" if n["security"].lower() in ("wpa", "wpa2") else n["security"].lower()
                    break
        except Exception as e:
            print(f"(scan dilewati: {e})", file=sys.stderr)
        print("Simpan jaringan:", st.save_network(a.ssid, a.wifi_password, sec))
        print("Menyambungkan...")
        print("Tersambung:", st.connect_network(a.ssid))
    elif a.cmd == "forget":
        print(st.forget_network(a.ssid))
    elif a.cmd == "passthrough":
        print(st.set_passthrough(a.mode == "on"))
    elif a.cmd == "ls":
        for href, is_dir, size, mod in st.ls(a.path):
            print(f"{'<DIR>' if is_dir else size:>12}  {mod:<30} {href}")
    elif a.cmd == "get":
        st.get(a.remote, a.local or os.path.basename(a.remote)); print("OK")
    elif a.cmd == "put":
        st.put(a.local, a.remote or "/" + os.path.basename(a.local)); print("OK")
    elif a.cmd == "mkdir":
        st.mkdir(a.path); print("OK")
    elif a.cmd == "rm":
        st.rm(a.path); print("OK")


if __name__ == "__main__":
    try:
        main()
    except requests.exceptions.ConnectionError:
        sys.exit("Tidak bisa menghubungi stick. Pastikan laptop tersambung ke WiFi 'SanDisk Connect ...'.")
    except (RuntimeError, TimeoutError, ValueError) as e:
        sys.exit(f"Error: {e}")
