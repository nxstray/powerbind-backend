# OWASP IoT Top 10 (2018) - PowerBind

Checklist untuk sisi perangkat & protokol: ESP32, Mosquitto (MQTT), relay, dan
alur data sensor ke backend. Ini area paling berisiko di PowerBind karena
menyentuh perangkat fisik.

Kolom Status: `Belum`, `Sebagian`, `OK`, `N/A`.

---

## I1 - Weak, Guessable, or Hardcoded Passwords

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Kredensial MQTT per perangkat, bukan satu user/password default yang sama untuk semua | konfigurasi `mosquitto`, `docker-compose.yml` |
| Belum | Tidak ada password default `admin/admin`-style yang tersisa di dev/prod | `.env`, compose, firmware ESP32 |
| Belum | Tidak ada kredensial perangkat yang ditulis di repo atau di firmwares | pencarian di repo + sketsa Arduino |

## I2 - Insecure Network Services

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Port MQTT (1883/8883) tidak terbuka ke jaringan luas tanpa perlu | `docker-compose.yml`, firewall host |
| Belum | Tidak ada layanan diagnostik perangkat (web server ESP32, telnet, OTA tanpa auth) yang terbuka | firmware ESP32 |
| Belum | Mosquitto tidak mengizinkan anonymous client | `mosquitto.conf` |

## I3 - Insecure Ecosystem Interfaces

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Web/API/cloud interface (backend + Grafana + Prometheus) tidak default-terbuka | `docker-compose.yml`, `SecurityConfig` |
| Belum | Grafana/SonarQube/InfluxDB tidak memakai password default | `.env`, compose |
| Belum | Topik MQTT dibatasi per perangkat: perangkat A tidak bisa mempublish ke topik perangkat B | `mosquitto` ACL, `MqttMessageHandler` |

## I4 - Lack of Secure Update Mechanism

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Cara update firmware ESP32 (OTA/USB) punya verifikasi sumber (tanda tangan/checksum) | prosedur + firmware |
| Belum | Update tidak bisa dipicu oleh pihak yang tidak berwenang | prosedur OTA |
| Belum | Versi firmware perangkat tercatat dan bisa diaudit | skema data perangkat |

## I5 - Use of Insecure or Outdated Components

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | CVE dependensi backend dipantau | `run-sbom-backend.ps1` + SCA/Trivy |
| Belum | CVE dependensi frontend dipantau | `run-sbom-frontend.ps1` + `npm audit` |
| Belum | Versi library MQTT/firmware ESP32 diperbarui berkala | pengelolaan firmware |
| Sebagian | CVE paket OS di image Docker | (opsional) `trivy image` |

## I6 - Insufficient Privacy Protection

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Data kehadiran penghuni (presence) dianggap data pribadi: siapa yang boleh mengakses, berapa lama disimpan | API + retensi InfluxDB/Loki |
| Belum | Riwayat pesan percakapan AI tidak disimpan/ditampilkan ke user lain | `AgentService`, `MemoryService` |
| Belum | Data yang tidak perlu tidak dikumpulkan (minimalisasi) | tinjauan skema data |

## I7 - Insecure Data Transfer and Storage

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | MQTT memakai TLS (8883) atau minimal berada di jaringan tepercaya yang jelas batasnya | mosquitto + topologi jaringan |
| Belum | HTTP API memakai HTTPS di produksi | nginx/reverse proxy |
| Belum | Data sensitif di database tidak dalam bentuk plaintext | Postgres, InfluxDB |
| Belum | Backup database terenkripsi/dilindungi | prosedur operasional |

## I8 - Lack of Device Management

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Ada daftar aset perangkat (siapa pemilik, topik MQTT, lokasi) | skema data + dokumentasi |
| Belum | Perangkat yang dicabut/dipindah bisa di-nonaktifkan dari sisi server | backend + MQTT ACL |
| Belum | Perubahan state relay bisa ditelusuri ke sumber (perangkat atau user) | audit log |

## I9 - Insecure Default Settings

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Tidak ada kredensial default yang masih aktif di compose (Postgres, InfluxDB, Grafana, Mosquitto, MinIO bila ada) | `docker-compose.yml` |
| Belum | Perangkat dikirim/di-deploy tanpa password default yang diketahui | firmware + prosedur provisioning |
| Belum | Mode debug/verbose mati di produksi (backend, mosquitto, mosquitto log payload) | konfigurasi container |

## I10 - Lack of Physical Hardening

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| N/A | Akses fisik ke port debug/UART perangkat | (bila perangkat dipasang di lokasi publik, jadikan `Belum`) |
| Belum | Kuncian/penempatan perangkat & kabel relay tidak mudah dicabut atau di-bypass | instalasi fisik |
| N/A | Proteksi terhadap dumping firmware lewat port debug | (opsional, tergantung model ancaman) |

---

## Urutan kerja yang disarankan

1. I1 + I9 - kredensial default & hardcoded. Ini temuan paling sering dan paling mudah diperbaiki.
2. I2 + I3 - autentikasi & ACL Mosquitto, batasi topik per perangkat.
3. I7 - TLS untuk MQTT dan HTTPS untuk API.
4. I5 - mulai pantau CVE dependensi (SBOM).
5. I6 + I8 - privasi data kehadiran, daftar aset perangkat, audit trail perintah relay.
6. I4 + I10 - mekanisme update aman & hardening fisik.
