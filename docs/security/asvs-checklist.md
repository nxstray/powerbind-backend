# OWASP ASVS - subset topik relevan PowerBind

ASVS (Application Security Verification Standard) adalah daftar verifikasi
menyeluruh. Dokumen ini **bukan** ASVS lengkap: ini pemetaan topik per bab ASVS 5.0
yang relevan untuk PowerBind, supaya bisa dikerjakan bertahap.

Kolom Status: `Belum`, `Sebagian`, `OK`, `N/A`.

Penting: nomor requirement resmi tidak dikutip di sini (ASVS 5.0 menomori ulang
semuanya). Untuk audit formal, cocokkan tiap topik ke nomor requirement di
sumber resmi ASVS 5.0.

Target yang disarankan: **Level 1 penuh dulu** (semua item L1), baru sebagian L2.

---

## V1 - Encoding and Sanitization

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Input user tidak pernah dirangkai langsung menjadi query (SQL/Flux/PromQL) - pakai parameter/prepared statement | `InfluxDBService` (Flux), `PrometheusService` (guard metrik), JPA repository |
| 1 | Belum | Output markdown/HTML dari AI dibersihkan sebelum dirender ke DOM | `marked` + `dompurify` di frontend |
| 2 | Belum | Encoding konteks benar (HTML, atribut, URL, JS) - bukan satu escape untuk semua konteks | komponen Vue yang menampilkan pesan AI/log |

## V2 - Validation and Business Logic

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Semua input divalidasi di server (bukan hanya di UI): tipe, rentang, panjang, format | DTO `data/request/` + `@Valid` |
| 1 | Belum | Nilai numerik dari parameter dibatasi (mis. `hours` pada query metrik & power history) | `PrometheusService`, `InfluxDBService.queryPowerHistory` |
| 2 | Belum | Urutan/state bisnis dijaga: relay tidak bisa ON tanpa syarat yang benar, tidak ada TOCTOU | `RoomService`, `RoomTimeoutService`, `MqttMessageHandler` |

## V3 - Web Frontend Security

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Token tidak disimpan di tempat yang bisa dibaca skrip pihak ketiga tanpa proteksi (localStorage vs httpOnly cookie - ambil keputusan sadar) | `authStore`, `utils/api.js` |
| 1 | Belum | Tidak ada `v-html` tanpa sanitasi | pencarian `v-html` di `src/` |
| 2 | Belum | Header keamanan & CSP di nginx | konfigurasi nginx frontend |

## V4 - API and Web Service

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Setiap endpoint memeriksa autentikasi DAN otorisasi objek (lihat API Top 10 API1/API5) | controller + `SecurityConfig` |
| 1 | Belum | Metode HTTP & status code dipakai benar; tidak ada endpoint "bebas" di luar skema | `/v3/api-docs` |
| 2 | Belum | Rate limit & kuota per user pada endpoint mahal | `AgentController` |

## V5 - File Handling

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Upload dibatasi tipe (allowlist) dan ukuran; nama file tidak dipakai mentah di path | `DocumentService`, konfigurasi multipart |
| 1 | Belum | Isi dokumen diperlakukan sebagai data (tidak dieksekusi), dan dipotong panjangnya | `DocumentService.extractText` (cap 15000 char) |

## V6 - Authentication

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Sebagian | Ada autentikasi berbasis JWT | `AuthService` |
| 1 | Belum | Password disimpan dengan algoritma kuat (bcrypt/argon2) + salt | `SecurityConfig` |
| 1 | Belum | Kebijakan panjang/kompleksitas password minimal dan tidak membatasi berlebihan | `AuthService`, UI login |
| 2 | Belum | Proteksi brute force (rate limit / lockout sementara) | `AuthService` |
| 2 | Belum | Perubahan password mencabut sesi/token lama | `AuthService`, `ChangePasswordModal` |

## V7 - Session Management

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Logout benar-benar mengakhiri sesi (token tidak bisa dipakai lagi) | `AuthService` |
| 2 | Belum | Refresh token punya rotasi atau deteksi pemakaian ulang | `AuthService` |

## V8 - Authorization

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Otorisasi dicek di server untuk setiap aksi, termasuk aksi per-room | `RoomService`, controller |
| 1 | Belum | Aksi admin terpisah dari user biasa (least privilege) | `SecurityConfig`, `AdminErdDataService` |
| 2 | Belum | Menolak by default: rute baru otomatis butuh autentikasi kecuali dinyatakan terbuka | `SecurityConfig` |

## V9 - Self-contained Tokens (JWT)

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Algoritma ditetapkan eksplisit saat verifikasi (menolak `none`/algoritma lain) | filter JWT di `SecurityConfig` |
| 1 | Belum | Secret key cukup kuat dan dibaca dari konfigurasi, bukan hardcoded | `application.properties` / `.env` |
| 1 | Belum | `exp`, `iat`, `iss` diverifikasi | `AuthService` |
| 2 | Belum | Token tidak bisa dipakai ulang setelah user dinonaktifkan | `AuthService` |

## V10 - OAuth and OIDC

`N/A` - PowerBind memakai autentikasi sendiri (username/password + JWT), bukan
OAuth/OIDC. Bab ini baru relevan kalau nanti ditambahkan login Google/SSO.

## V11 - Cryptography

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Tidak ada kriptografi buatan sendiri; hanya library terpelihara | `AuthService`, `utils/jwt.js` |
| 1 | Belum | Tidak ada MD5/SHA1 untuk keperluan keamanan | pencarian di `src/main/java` |
| 2 | Belum | Kunci/secret tidak ikut ke log atau pesan error | logging aplikasi |

## V12 - Secure Communication (paling relevan untuk MQTT)

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | HTTP produksi memakai TLS (bukan hanya localhost) | nginx / reverse proxy |
| 1 | Belum | MQTT memakai TLS dan autentikasi (bukan anonymous di port 1883 terbuka) | konfigurasi `mosquitto` di `docker-compose.yml` |
| 2 | Belum | Sertifikat & cipher dikonfigurasi, tidak memakai default lemah | nginx, mosquitto |

## V13 - Configuration

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Sebagian | Secret lewat environment/`.env`, tidak di-commit | `.env`, `.env.example`, `docker-compose.yml` |
| 1 | Belum | Debug/dev mode mati di produksi (`spring.jpa.show-sql`, Swagger terbuka, dsb.) | `application.properties` |
| 1 | Belum | Dependency bebas dari CVE yang diketahui, dan diperiksa berkala | `run-sbom-*.ps1` + SCA/Trivy |
| 2 | Belum | Port tidak dipublikasikan lebih luas dari yang perlu (Prometheus/Grafana/SonarQube tidak ke publik) | `docker-compose.yml` |

## V14 - Data Protection

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Data sensitif (password, token, data kehadiran penghuni) tidak masuk log | logging + `LogController` |
| 1 | Belum | Backup Postgres/InfluxDB ada dan dilindungi | prosedur operasional |
| 2 | Belum | Data kehadiran diperlakukan sebagai data pribadi: retensi & akses dibatasi | kebijakan + query API |

## V15 - Secure Coding and Architecture

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Sebagian | Analisis statis otomatis berjalan di kedua repo | SonarQube (Quality Gate OK) |
| 1 | Belum | Error tidak mengekspos detail internal ke klien | handler error global |
| 2 | Belum | Ada threat model tertulis untuk alur IoT | (usulan: OWASP Threat Dragon) |

## V16 - Security Logging and Error Handling

| Lv | Status | Yang perlu dicek | Cek di |
|---|---|---|---|
| 1 | Belum | Kejadian keamanan dicatat: login sukses/gagal, perubahan password, perintah relay, akses ditolak | `AuthService`, `RoomService`, `LogController` |
| 1 | Belum | Log tidak bisa diubah/dihapus oleh user aplikasi | Loki + kebijakan retensi |
| 2 | Belum | Exception tidak "ditelan" diam-diam pada jalur kritis | temuan `catch` kosong dari SonarQube |

---

## Urutan kerja yang disarankan

1. V6 + V9 - password hashing, verifikasi JWT, pencabutan token. Ini fondasi keamanan aplikasi.
2. V12 - TLS untuk MQTT dan HTTPS untuk API.
3. V8 + V4 - otorisasi per objek & per fungsi (beririsan dengan API Top 10 API1/API5).
4. V13 - matikan mode dev, batasi port, mulai pantau CVE.
5. V16 + V14 - audit log dan perlindungan data pribadi.
6. V1 + V2 + V3 + V5 - sanitasi, validasi, keamanan frontend, penanganan file.

