# Stick Connect

Aplikasi Android pengganti *SanDisk Connect / Connect Drive* (sudah hilang dari Play Store) untuk **SanDisk Connect Wireless Stick**.

## Fitur
- **File**: lihat isi stick, upload, download, buat folder, ganti nama, hapus (WebDAV).
- **Backup otomatis** foto & video per album ke `/Backup/<HP>/<album>/`, tanpa duplikat.
- **WiFi rumah**: scan, sambungkan stick ke WiFi rumah, hapus WiFi tersimpan, pass-through.
- **Stick**: baterai, kapasitas, firmware, ganti nama/password WiFi stick, auto-mati.

Stick ditemukan otomatis lewat WiFi rumah (IP terakhir / Bonjour `_airstash._tcp`) atau WiFi stick (`172.25.63.1`).

## Build
Setiap push ke `main` membangun APK lewat GitHub Actions. APK terbaru ada di **Releases → latest**.

## Protokol (dari APK resmi 2.1.1)
| Fungsi | Request |
|---|---|
| Info | `GET /settings.xml` |
| Scan WiFi | `POST group=scan` lalu poll `GET /settings.xml?group=scan` |
| Simpan WiFi | `POST group=saved&action=save&ssid=…&security=wpa&password=<PBKDF2-SHA1(pw, ssid, 4096, 32)>` |
| Sambungkan | `POST group=saved&action=connect&ssid=…`, poll `<client status="connected" ip="…">` |
| Pass-through | `POST sidelinken=true/false` |
| File | WebDAV di `/files/…` |
| Auth | HTTP Digest, user `owner`, password = password WiFi stick |

`tools/sandisk_stick.py` adalah klien CLI Python untuk protokol yang sama.
