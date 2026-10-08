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

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Input user tidak pernah dirangkai langsung menjadi query (SQL/Flux/PromQL) - pakai parameter/prepared statement | JPA Hibernate prepared statements; `PrometheusService.sanitize()` regex whitelist; `InfluxDBService.sanitizeHours()` & `sanitizeTag()`. |
| 1 | OK | Output markdown/HTML dari AI dibersihkan sebelum dirender ke DOM | `MarkdownRenderer.vue` menggunakan `DOMPurify.sanitize()` untuk markdown HTML dan SVG Mermaid diagram. |
| 2 | OK | Encoding konteks benar (HTML, atribut, URL, JS) - bukan satu escape untuk semua konteks | Vue 3 template auto-escaping untuk text binding (`{{ }}`); `DOMPurify` khusus untuk dynamic `v-html`. |

## V2 - Validation and Business Logic

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Semua input divalidasi di server (bukan hanya di UI): tipe, rentang, panjang, format | Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Size`) di seluruh request DTO (`AuthRequest`, `RoomRequest`, `AgentRequest`, `ErdExplainRequest`). |
| 1 | OK | Nilai numerik dari parameter dibatasi (mis. `hours` pada query metrik & power history) | `InfluxDBService.sanitizeHours` membatasi 1-168 jam; `PrometheusService.queryRange` memvalidasi opsi step & window; `AdminLogController` membatasi limit log. |
| 2 | OK | Urutan/state bisnis dijaga: relay tidak bisa ON tanpa syarat yang benar, tidak ada TOCTOU | `RoomService.updatePresence`, `RoomTimeoutService` (auto-off 120 detik), `MqttMessageHandler` sinkronisasi state hardware. |

## V3 - Web Frontend Security

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Token tidak disimpan di tempat yang bisa dibaca skrip pihak ketiga tanpa proteksi (localStorage vs httpOnly cookie - ambil keputusan sadar) | Keputusan sadar: JWT disimpan di localStorage untuk arsitektur decoupled SPA + mobile/IoT; dilindungi CSP & zero-XSS policy via DOMPurify. |
| 1 | OK | Tidak ada `v-html` tanpa sanitasi | Seluruh rendering `v-html` di `MarkdownRenderer.vue` melalui pipa `DOMPurify.sanitize()`. |
| 2 | OK | Header keamanan & CSP di nginx / backend | `SecurityConfig` menginjeksikan `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `HSTS`, `Referrer-Policy`, `Cross-Origin-Resource-Policy: same-site`. |

## V4 - API and Web Service

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | Sebagian | Setiap endpoint memeriksa autentikasi DAN otorisasi objek (lihat API Top 10 API1/API5) | Autentikasi menyeluruh via `JwtAuthFilter`; percakapan AI memvalidasi kepemilikan (`findOwnedConversation`); endpoint room saat ini shared antar-keluarga. |
| 1 | OK | Metode HTTP & status code dipakai benar; tidak ada endpoint "bebas" di luar skema | `GlobalExceptionHandler` memetakan HTTP 405 (Method Not Allowed), 415 (Media Type), 400 (Bad Request), 404 (Not Found) secara ketat. Terverifikasi ZAP DAST (0 false 500s). |
| 2 | OK | Rate limit & kuota per user pada endpoint mahal | `RedisRateLimitFilter` membatasi 30 req/60s (umum) dan 10 req/60s (auth); Groq dilindungi CircuitBreaker & Retry. |

## V5 - File Handling

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Upload dibatasi tipe (allowlist) dan ukuran; nama file tidak dipakai mentah di path | `DocumentService.isDocument` memeriksa tipe MIME (PDF, DOCX, TXT); file diproses in-memory stream tanpa pernah disimpan ke filesystem disk mentah. |
| 1 | OK | Isi dokumen diperlakukan sebagai data (tidak dieksekusi), dan dipotong panjangnya | `DocumentService.extractText` mengekstrak teks via Apache Tika dan memotong batas maksimum 15.000 karakter (`truncate`). Diuji unit test `DocumentServiceTest`. |

## V6 - Authentication

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Ada autentikasi berbasis JWT | `AuthService`, `JwtAuthFilter`, `JwtUtil`. |
| 1 | OK | Password disimpan dengan algoritma kuat (bcrypt/argon2) + salt | `SecurityConfig` (`BCryptPasswordEncoder` default strength 10). |
| 1 | OK | Kebijakan panjang/kompleksitas password minimal dan tidak membatasi berlebihan | DTO `AuthRequest.ChangePassword` memvalidasi minimal 8 karakter (`@Size(min=8)`). |
| 2 | OK | Proteksi brute force (rate limit / lockout sementara) | `AuthService`: Lockout akun selama 10 menit setelah 5x salah password; `RedisRateLimitFilter` membatasi 10 req/menit per IP. |
| 2 | OK | Perubahan password mencabut sesi/token lama | `AuthService.changePassword` otomatis memanggil `refreshTokenRepository.deleteAllByUser(user)` sehingga seluruh sesi lama hangus. |

## V7 - Session Management

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Logout benar-benar mengakhiri sesi (token tidak bisa dipakai lagi) | `AuthService.logout` mencabut refresh token (`revoked = true`) di database Postgres. |
| 2 | OK | Refresh token punya rotasi atau deteksi pemakaian ulang | `AuthService.refresh` menghasilkan token pair baru, mencabut token lama, dan mendeteksi reuse (bila token revoked dipakai lagi, seluruh sesi user dicabut). |
| 2 | OK | Token/sesi kedaluwarsa tidak menumpuk di database | `RefreshTokenCleanupService` (cron 03:30 UTC harian) menyapu token yang lewat `expiresAt` lewat `deleteByExpiresAtBefore(now)`; token revoked-belum-kedaluwarsa sengaja disimpan karena dipakai deteksi reuse. Terverifikasi `RefreshTokenCleanupServiceTest` + service ikut konteks Spring (`ApplicationSmokeTest`). |

## V8 - Authorization

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | Sebagian | Otorisasi dicek di server untuk setiap aksi, termasuk aksi per-room | Seluruh endpoint REST berada di balik `authenticated()`; otorisasi per percakapan AI sudah dicek (`findOwnedConversation`). |
| 1 | OK | Aksi admin terpisah dari user biasa (least privilege) | Rute admin (`/api/admin/erd`, `/api/admin/logs`, `/api/admin/metrics`) dilindungi `@PreAuthorize("hasRole('ADMIN')")`. |
| 2 | OK | Menolak by default: rute baru otomatis butuh autentikasi kecuali dinyatakan terbuka | `SecurityConfig` diakhiri dengan `.anyRequest().authenticated()`. |

## V9 - Self-contained Tokens (JWT)

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Algoritma ditetapkan eksplisit saat verifikasi (menolak `none`/algoritma lain) | `JwtUtil` menggunakan HMAC-SHA256 (`Keys.hmacShaKeyFor`) secara eksplisit via library JJWT terverifikasi. |
| 1 | OK | Secret key cukup kuat dan dibaca dari konfigurasi, bukan hardcoded | Kunci JWT dibaca via `${JWT_SECRET}` di `.env` (256-bit key). |
| 1 | OK | `exp`, `iat`, `iss` diverifikasi | JJWT parser memvalidasi claims `exp` (1 jam untuk access token) dan melempar `ExpiredJwtException` jika kadaluarsa. |
| 2 | OK | Token tidak bisa dipakai ulang setelah user dinonaktifkan / lockout | User dicek saat autentikasi & validasi; status `lockedUntil` memblokir login. |

## V10 - OAuth and OIDC

`N/A` - PowerBind memakai autentikasi sendiri (username/password + JWT), bukan
OAuth/OIDC. Bab ini baru relevan kalau nanti ditambahkan login Google/SSO.

## V11 - Cryptography

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Tidak ada kriptografi buatan sendiri; hanya library terpelihara | Menggunakan Spring Security Crypto (`BCrypt`) dan `io.jsonwebtoken` (JJWT 0.12.x). |
| 1 | OK | Tidak ada MD5/SHA1 untuk keperluan keamanan | Tidak ada penggunaan hash MD5/SHA1 usang di security codebase. |
| 2 | OK | Kunci/secret tidak ikut ke log atau pesan error | `.gitignore` mengecualikan `.env`; logging controller tidak memuntahkan token atau payload kredensial. |

## V12 - Secure Communication (paling relevan untuk MQTT)

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | HTTP produksi memakai TLS (bukan hanya localhost) | Caddy (`caddy/Caddyfile` + service `caddy`): redirect 308 ke HTTPS + TLS aktif; `SITE_ADDRESS` di `.env` — localhost memakai internal CA, domain memakai Let's Encrypt. Terverifikasi curl `https://localhost/...`. |
| 1 | OK | MQTT memakai TLS dan autentikasi (bukan anonymous di port 1883 terbuka) | Autentikasi wajib di semua listener (`allow_anonymous false`, password + ACL dari `.env`); TLS listener 8883 (sertifikat via `run-mosquitto-certs.ps1`); backend container mengirim `MQTT_USERNAME`/`MQTT_PASSWORD`. Terverifikasi uji live: anonim ditolak + pub/sub via 8883. |
| 2 | Sebagian | Sertifikat & cipher dikonfigurasi, tidak memakai default lemah | Caddy mengelola sertifikat (internal CA localhost / Let's Encrypt domain publik, TLS 1.2+ default); Mosquitto memakai self-signed (`run-mosquitto-certs.ps1`). Audit cipher end-to-end per device belum. |

## V13 - Configuration

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Secret lewat environment/`.env`, tidak di-commit | Seluruh secret (`JWT_SECRET`, database pass, Influx token, Groq key) dimuat via `.env` dan diabaikan Git (`.gitignore`). |
| 1 | OK | Debug/dev mode mati di produksi (`spring.jpa.show-sql`, Swagger terbuka, dsb.) | `spring.jpa.show-sql=false`, `management.endpoint.health.show-details=never`. |
| 1 | OK | Dependency bebas dari CVE yang diketahui, dan diperiksa berkala | Pipeline CycloneDX SBOM terkonfigurasi di backend (`run-sbom-backend.ps1`) dan frontend (`run-sbom-frontend.ps1`); dependency tree bersih. |
| 1 | OK | Alerting untuk kondisi produksi (backend mati, error 5xx, heap, pool DB) | `monitoring/prometheus/rules/powerbind-alerts.yml` (4 rule: `PowerbindBackendDown`, `PowerbindServerErrorRate`, `PowerbindHeapUsageHigh`, `PowerbindDbPoolSaturated`) dimuat Prometheus via `rule_files`; terverifikasi `promtool check config` + `/api/v1/rules` (4 rules, semua `inactive` saat sehat). Alertmanager belum ada, jadi alert firing hanya terlihat di Prometheus UI `/alerts`. |
| 2 | OK | Port tidak dipublikasikan lebih luas dari yang perlu (Prometheus/Grafana/SonarQube tidak ke publik) | `docker-compose.yml` mengisolasi service backend dalam private network; Prometheus di-scrape internal. |

## V14 - Data Protection

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Data sensitif (password, token, data kehadiran penghuni) tidak masuk log | Password selalu di-hash sebelum disimpan; logger tidak memuntahkan plain credentials atau raw JWT header. |
| 1 | OK | Backup Postgres/InfluxDB ada dan dilindungi | Service `backup` di compose: `pg_dump` + `influx backup` terjadwal (cron harian, default 02:00 UTC) ke `./backups/` (git-ignored) dengan rotasi otomatis (`BACKUP_RETENTION_DAYS`, default 7 hari); volume Docker terisolasi. Terverifikasi uji live: dump + TSM shard masuk, log `backup OK`. |
| 2 | OK | Data kehadiran diperlakukan sebagai data pribadi: retensi & akses dibatasi | Bucket InfluxDB `smarthome` dikhususkan untuk time-series presence; endpoint querying terlindung autentikasi JWT. |

## V15 - Secure Coding and Architecture

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Analisis statis otomatis berjalan di kedua repo | SonarQube Community Server (kualitas gate Clean/Passing, SAST terverifikasi). |
| 1 | OK | Error tidak mengekspos detail internal ke klien | `GlobalExceptionHandler` menangkap seluruh unhandled exception, mengembalikan pesan terstruktur tanpa stack trace. Terverifikasi ZAP DAST. |
| 2 | Sebagian | Ada threat model tertulis untuk alur IoT | Didokumentasikan di `docs/security/iot-top-10-checklist.md` dan `api-top-10-checklist.md`. |

## V16 - Security Logging and Error Handling

| Lv | Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|---|
| 1 | OK | Kejadian keamanan dicatat: login sukses/gagal, perubahan password, perintah relay, akses ditolak | `AuthService` mencatat peringatan token reuse, lockout, dan failure; `RoomService` mencatat event offline & relay switch; log dikirim terpusat ke Grafana Loki. |
| 1 | OK | Log tidak bisa diubah/dihapus oleh user aplikasi | Loki menyimpan stream log append-only; user biasa tidak memiliki akses ke database log atau Grafana instance. |
| 2 | OK | Exception tidak "ditelan" diam-diam pada jalur kritis | Seluruh exception penting di-log dengan level ERROR/WARN (`AuthService`, `GroqService`, `MqttConfig`). |

---

## Urutan kerja yang disarankan

1. V6 + V9 - password hashing, verifikasi JWT, pencabutan token (Sudah OK).
2. V13 + V16 - konfigurasi secret, SBOM CVE tracker, dan security audit log (Sudah OK).
3. V1 + V2 + V3 + V5 - sanitasi, validasi DTO, DOMPurify frontend, penanganan file aman (Sudah OK).
4. V12 - penambahan terminasi TLS untuk MQTT & HTTPS saat deployment ke domain publik VPS/cloud.
5. V16 + V14 - audit log dan perlindungan data pribadi.
6. V1 + V2 + V3 + V5 - sanitasi, validasi, keamanan frontend, penanganan file.

