# OWASP API Security Top 10 (2023) - PowerBind

Checklist untuk REST API backend. Kolom Status: `Belum`, `Sebagian`, `OK`, `N/A`.

Legenda kolom "Cek di / Bukti": petunjuk kode, implementasi, atau tes yang memverifikasi kepatuhan.

---

## API1:2023 - Broken Object Level Authorization (BOLA/IDOR)

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| Sebagian | Setiap endpoint yang menerima ID (room id, conversation id, message id) memverifikasi bahwa objek itu benar milik user yang login - bukan hanya "objek ada" | `AgentService.findOwnedConversation` memvalidasi kepemilikan percakapan per user login (`findOwnedConversation`). Endpoint room saat ini masih shared/global antar penghuni rumah. |
| Sebagian | User biasa tidak bisa membaca/mengubah room atau percakapan milik user lain dengan menebak UUID | Percakapan AI aman (`findOwnedConversation` melempar `ResourceNotFoundException`). Room dikelola secara kolektif oleh anggota keluarga. |
| Sebagian | `PATCH` relay (`relayOn`) memeriksa kepemilikan/izin, bukan hanya validitas JWT | Memerlukan JWT aktif; kontrol relay diperbolehkan untuk seluruh pengguna terdaftar keluarga (`Role.USER` / `Role.ADMIN`). |

## API2:2023 - Broken Authentication

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | JWT dipakai untuk autentikasi | `JwtAuthFilter`, `JwtUtil`, `SecurityConfig` (HMAC SHA-256 dengan secret aman). |
| OK | Kekuatan hashing password (bcrypt/argon2, cost memadai) - bukan MD5/SHA polos/plaintext | `SecurityConfig` (`BCryptPasswordEncoder` default cost 10) digunakan di `AuthService` dan `DataInitializer`. |
| OK | Masa berlaku access token & refresh token wajar; refresh bisa dicabut saat logout | `AuthService`: Access token 1 jam, refresh token 7 hari (rotasi otomatis, token lama dicabut di tabel `refresh_tokens`, deteksi reuse mencabut seluruh sesi). |
| OK | Tidak ada endpoint sensitif yang terbuka tanpa autentikasi (`permitAll` berlebihan) | `SecurityConfig`: Hanya `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh`, `/api/logs`, `/actuator/health`, `/actuator/prometheus`, `/ws/**`, dan Swagger docs yang dibuka; sisanya `authenticated()`. |
| OK | Percobaan login gagal tidak membocorkan "user ada/tidak ada" dan tidak bisa di-brute force tanpa batas | `AuthService`: Pesan error generik ("Invalid username or password"); lockout otomatis setelah 5 kegagalan berturut-turut selama 10 menit (`AccountLockedException`); dilindungi rate limiter Redis (`RedisRateLimitFilter`: 10 req/60s). |

## API3:2023 - Broken Object Property Level Authorization

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Request DTO tidak menerima field yang tidak seharusnya (mass assignment) - mis. `id`, `relayOn`, `role` dari body user | DTO terpisah di `data/request/` (`AuthRequest`, `RoomRequest`, `AgentRequest`), entitas database tidak di-bind langsung dari JSON request body. |
| OK | Response tidak mengembalikan field internal (hash password, token internal, detail stack) | DTO terpisah di `data/response/` (`AuthResponse.Profile`, `RoomResponse`, dll.), `password` dan `lockedUntil` tidak pernah diserialisasi ke response. |

## API4:2023 - Unrestricted Resource Consumption

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Pagination & batas ukuran pada endpoint list (log, pesan percakapan, baris tabel ERD) | `AdminLogController` (`limit` default 300, max 1000), `AdminErdDataService` (paged SQL LIMIT/OFFSET), `InfluxDBService` (sanitized max 168 hours). |
| OK | Batas panjang input pada body/parameter (prompt agent, nama room, filter) | DTO di `data/request/` menggunakan `@NotBlank`, `@Size(min=8)` pada password, sanitasi regex di `PrometheusService` dan `InfluxDBService`. |
| OK | Rate limit pada endpoint mahal (agent chat, quick-ask, transcribe, vision) | `RedisRateLimitFilter` membatasi 30 req/60s untuk API umum dan 10 req/60s untuk API auth; Groq dilindungi `Resilience4j` Circuit Breaker & Retry. |
| OK | Upload dokumen dibatasi ukuran & tipe file | `DocumentService` memvalidasi tipe file (`isDocument`: PDF, DOCX, TXT) dan membatasi teks yang diekstrak maksimal 15.000 karakter (`extractText`). |

## API5:2023 - Broken Function Level Authorization

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Endpoint admin (ERD admin, query log, metrik) hanya untuk role admin - dicek di server, bukan hanya disembunyikan di UI | `@PreAuthorize("hasRole('ADMIN')")` aktif di `AdminErdController`, `AdminLogController`, dan `AdminMetricsController`; diverifikasi oleh Spring Method Security. |
| OK | Tidak ada endpoint yang bergantung pada UI untuk menyembunyikan aksi berbahaya | Otorisasi server-side dieksekusi sebelum controller method berjalan; request unauthorized otomatis ditolak HTTP 403. |

## API6:2023 - Unrestricted Access to Sensitive Business Flows

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Aksi fisik (menyalakan relay, mengubah setelan perangkat) punya proteksi terhadap penyalahgunaan massal/otomatis | `RoomService.setRelay` berada di balik autentikasi JWT dan rate limit IP, ada logika auto-off keselamatan setelah 120 detik tanpa kehadiran (`RoomTimeoutService`). |
| Sebagian | Perubahan state perangkat tercatat (audit trail: siapa, kapan, room mana) | Log operasional tercatat di console & Loki via `RoomService` dan `MqttMessageHandler`; tabel audit trail terpisah untuk user spesifik belum dibuat. |

## API7:2023 - Server Side Request Forgery (SSRF)

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | URL/parameter yang dipakai server untuk memanggil resource lain (Prometheus, InfluxDB, Groq, Grafana/Loki) tidak bisa dikendalikan user | Base URL untuk Prometheus, InfluxDB, Groq, dan Loki dibaca statis dari `application.properties`/`.env`, tidak ada parameter input user yang digunakan sebagai target HTTP host. |
| OK | Nama metrik di quick-ask divalidasi terhadap daftar metrik yang dikenal sebelum masuk ke query | `PrometheusService.sanitize()` memvalidasi parameter metrik dan label hanya mengizinkan regex `^[a-zA-Z_:][a-zA-Z0-9_:]*$` sebelum dibentuk menjadi query PromQL. |

## API8:2023 - Security Misconfiguration

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | CORS dibatasi ke origin yang benar (bukan `*` dengan credentials) | `SecurityConfig.corsConfigurationSource()` membaca `cors.allowed-origins` eksplisit dari environment (bukan wildcard). |
| OK | Header keamanan aktif (HSTS, X-Content-Type-Options, CSP, X-Frame-Options, CORP) | `SecurityConfig`: `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Strict-Transport-Security`, `Referrer-Policy`, `Cross-Origin-Resource-Policy: same-site`. Lolos ZAP scan (117 PASS rules). |
| OK | Endpoint actuator tidak mengekspos informasi sensitif (`env`, `heapdump`, `beans`) | `application.properties`: `management.endpoints.web.exposure.include=health,prometheus,metrics`, `management.endpoint.health.show-details=never`. |
| OK | Swagger/`/v3/api-docs` tidak terbuka di produksi tanpa proteksi | Gating lewat `SPRINGDOC_ENABLED`: compose mengisi `false`, sehingga `springdoc.api-docs.enabled` + `springdoc.swagger-ui.enabled` mati dan `/v3/api-docs` + `/swagger-ui/**` menjawab 404 di deployment container (terverifikasi live); flow dev `mvn spring-boot:run` tetap menyala karena default propertinya `true`. Rute Swagger juga tetap dibatasi header keamanan `SecurityConfig`. |
| OK | Detail error tidak mengembalikan stack trace ke klien | `GlobalExceptionHandler` menangkap seluruh error framework dan runtime, mengembalikan response JSON konsisten (`ApiResponse`), dan memetakan status HTTP 4xx/500 secara tepat. |
| OK | Kredensial tidak hardcoded di repo (compose, properties, script) | Seluruh password database, token Groq, JWT secret, dan InfluxDB token dialihkan ke variabel lingkungan `.env`. File `.env` masuk `.gitignore`. |

## API9:2023 - Improper Inventory Management

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Semua versi API terdokumentasi (tidak ada endpoint "lupa" yang masih hidup) | OpenAPI 3.0 / Swagger UI otomatis menginventarisasi 36 endpoint aktif di `/v3/api-docs`. |
| OK | Pemetaan port jelas (8045 backend, 5173 frontend, 9090 Prometheus, 3000 Grafana, 9000 SonarQube) dan tidak ada yang terekspos ke internet tanpa sengaja | `docker-compose.yml` mendefinisikan port mapping secara terstruktur untuk internal stack. |

## API10:2023 - Unsafe Consumption of APIs

| Status | Yang perlu dicek | Cek di / Bukti |
|---|---|---|
| OK | Respons dari API pihak ketiga (Groq) divalidasi sebelum dipakai - tidak langsung dipercaya sebagai HTML/JS | Frontend menggunakan `DOMPurify.sanitize()` di `MarkdownRenderer.vue` sebelum merender output markdown / SVG diagram dari AI ke dalam DOM. |
| OK | Timeout & penanganan error saat memanggil layanan eksternal | `GroqService` dilengkapi Resilience4j circuit breaker, retry exponential backoff, dan fallback respons saat kuota habis atau timeout. |

---

## Urutan kerja yang disarankan

1. API1 + API2 + API5 - ini yang paling berisiko pada aplikasi IoT multi-user (Auth & BFLA sudah OK, perkuat BOLA room jika ingin multi-tenant).
2. API8 - seluruh konfigurasi header keamanan, CORS, actuator, dan exception handling sudah OK dan diverifikasi ZAP.
3. API4 - rate limit, limit dokumen, dan pagination sudah OK.
4. API7 + API10 - validasi SSRF, PromQL sanitizer, dan DOMPurify sudah OK.
5. API3 + API6 + API9 - DTO terpisah dan manajemen port/inventarisasi sudah OK.
