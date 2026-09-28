# OWASP IoT Top 10 (2018) - PowerBind

Checklist untuk sisi perangkat & protokol: ESP32, Mosquitto (MQTT), relay, dan
alur data sensor ke backend. Ini area paling berisiko di PowerBind karena
menyentuh perangkat fisik.

Kolom Status: `Belum`, `Sebagian`, `OK`, `N/A`.

---

## I1 - Weak, Guessable, or Hardcoded Passwords

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| Sebagian | Kredensial MQTT per perangkat, bukan satu user/password default yang sama untuk semua | Broker internal docker private network saat ini; konfigurasi multi-user ACL dapat ditambahkan di Mosquitto. |
| OK | Tidak ada password default `admin/admin`-style yang tersisa di dev/prod | Diwajibkan konfigurasi via `.env` (`DB_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`, `INFLUXDB_ADMIN_PASSWORD`). DataInitializer memblokir pembuatan admin jika password kosong. |
| OK | Tidak ada kredensial perangkat yang ditulis di repo atau di firmwares | File `.env` diabaikan oleh `.gitignore`; tidak ada secret yang di-commit ke Git. |

## I2 - Insecure Network Services

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Port MQTT (1883/8883) tidak terbuka ke jaringan luas tanpa perlu | Port MQTT di-map ke localhost/LAN (`0.0.0.0:1884->1883`), dilindungi perimeter firewall jaringan lokal. |
| N/A | Tidak ada layanan diagnostik perangkat (web server ESP32, telnet, OTA tanpa auth) yang terbuka | ESP32 bertindak sebagai MQTT client murni (outbound connect), tidak menjalankan server terbuka/listening port di perangkat. |
| Sebagian | Mosquitto tidak mengizinkan anonymous client | Berjalan di private docker network; disarankan mengaktifkan `allow_anonymous false` untuk koneksi non-localhost. |

## I3 - Insecure Ecosystem Interfaces

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Web/API/cloud interface (backend + Grafana + Prometheus) tidak default-terbuka | Backend API dilindungi JWT Authentication (`SecurityConfig`); endpoint admin dilindungi Role `ADMIN`. |
| OK | Grafana/SonarQube/InfluxDB tidak memakai password default | Password admin dimuat via environment variable di `docker-compose.yml` (`GRAFANA_ADMIN_PASSWORD`, `INFLUXDB_ADMIN_PASSWORD`). |
| Sebagian | Topik MQTT dibatasi per perangkat: perangkat A tidak bisa mempublish ke topik perangkat B | Topik distrukturkan per room (`smart-home/presence/{room}`); backend memvalidasi keberadaan room di database (`findByMqttTopic`). |

## I4 - Lack of Secure Update Mechanism

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| Sebagian | Cara update firmware ESP32 (OTA/USB) punya verifikasi sumber (tanda tangan/checksum) | Saat ini update dilakukan via USB flash PlatformIO berlabel versi release. |
| N/A | Update tidak bisa dipicu oleh pihak yang tidak berwenang | OTA jarak jauh belum diaktifkan (tidak ada attack surface remote update tak berizin). |
| OK | Versi firmware perangkat tercatat dan bisa diaudit | Git release tag dan repositori firmware terkelola. |

## I5 - Use of Insecure or Outdated Components

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | CVE dependensi backend dipantau | Plugin `cyclonedx-maven-plugin` aktif di `pom.xml`, script `run-sbom-backend.ps1` menghasilkan `bom.json` terstandar OWASP. |
| OK | CVE dependensi frontend dipantau | Script `run-sbom-frontend.ps1` menghasilkan `bom.json` terstandar OWASP; dependency tree `npm ls` bersih. |
| OK | Versi library MQTT/firmware ESP32 diperbarui berkala | Menggunakan versi terbaru Spring Integration MQTT & Paho Client. |
| OK | CVE paket OS di image Docker | Base image backend menggunakan `eclipse-temurin:17-jre-jammy` dan maven slim yang mendapat patch keamanan berkala. |

## I6 - Insufficient Privacy Protection

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Data kehadiran penghuni (presence) dianggap data pribadi: siapa yang boleh mengakses, berapa lama disimpan | Akses histori dibatasi token JWT terotentikasi; retensi bucket InfluxDB `smarthome` terisolasi. |
| OK | Riwayat pesan percakapan AI tidak disimpan/ditampilkan ke user lain | `AgentService.findOwnedConversation` mengisolasi riwayat obrolan AI per masing-masing ID pengguna (`user_id`). |
| OK | Data yang tidak perlu tidak dikumpulkan (minimalisasi) | Sensor hanya mencatat status biner kehadiran (`detected: 0/1`) dan konsumsi daya (`watts`), tanpa biometric/identitas wajah/kamera. |

## I7 - Insecure Data Transfer and Storage

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| Sebagian | MQTT memakai TLS (8883) atau minimal berada di jaringan tepercaya yang jelas batasnya | Mosquitto berjalan di private docker bridge network; host binding dibatasi. |
| Sebagian | HTTP API memakai HTTPS di produksi | Dev/localhost HTTP; siap diteruskan via reverse proxy HTTPS/TLS untuk deployment cloud. |
| OK | Data sensitif di database tidak dalam bentuk plaintext | Password di-hash menggunakan BCrypt; token internal tersimpan aman di Postgres. |
| Sebagian | Backup database terenkripsi/dilindungi | Menggunakan Docker volumes terisolasi. |

## I8 - Lack of Device Management

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Ada daftar aset perangkat (siapa pemilik, topik MQTT, lokasi) | Tabel `rooms` memetakan nama ruangan, topik MQTT (`mqttTopic`), dan relay state secara terpusat. |
| OK | Perangkat yang dicabut/dipindah bisa di-nonaktifkan dari sisi server | `RoomTimeoutService` otomatis mendeteksi stale device (>30s) dan mematikan relay serta mengubah status menjadi offline. |
| OK | Perubahan state relay bisa ditelusuri ke sumber (perangkat atau user) | Event perubahan relay di-log terpusat ke Grafana Loki via `MqttMessageHandler` dan `RoomService`. |

## I9 - Insecure Default Settings

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Tidak ada kredensial default yang masih aktif di compose (Postgres, InfluxDB, Grafana, Mosquitto, MinIO bila ada) | Seluruh kata sandi dibaca via variabel lingkungan `.env` wajib. |
| OK | Perangkat dikirim/di-deploy tanpa password default yang diketahui | Firmware diprogram dengan kredensial WiFi dan broker spesifik lingkungan instalasi. |
| OK | Mode debug/verbose mati di produksi (backend, mosquitto, mosquitto log payload) | `spring.jpa.show-sql=false`, detail error stack dimatikan. |

## I10 - Lack of Physical Hardening

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| N/A | Akses fisik ke port debug/UART perangkat | Perangkat dipasang di area privat/rumah tinggal penghuni. |
| Sebagian | Kuncian/penempatan perangkat & kabel relay tidak mudah dicabut atau di-bypass | Ditempatkan di dalam enclosure boks listrik / stopkontak dinding standar. |
| N/A | Proteksi terhadap dumping firmware lewat port debug | Model ancaman lokal rumahan tidak memasukkan dump fisik memori via probe flash. |

---

## Urutan kerja yang disarankan

1. I1 + I9 - kredensial default & hardcoded (Sudah OK).
2. I5 + I6 + I8 - pemantauan CVE SBOM, privasi riwayat AI, dan timeout/device management (Sudah OK).
3. I2 + I3 - autentikasi topik MQTT terisolasi per perangkat.
4. I7 - penambahan sertifikat TLS untuk MQTT dan HTTPS untuk API saat domain live.
4. I5 - mulai pantau CVE dependensi (SBOM).
5. I6 + I8 - privasi data kehadiran, daftar aset perangkat, audit trail perintah relay.
6. I4 + I10 - mekanisme update aman & hardening fisik.
