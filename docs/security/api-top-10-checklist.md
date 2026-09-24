# OWASP API Security Top 10 (2023) - PowerBind

Checklist untuk REST API backend. Kolom Status: `Belum`, `Sebagian`, `OK`, `N/A`.

Legenda kolom "Cek di": petunjuk awal tempat melihat kode/konfigurasi, bukan hasil audit.

---

## API1:2023 - Broken Object Level Authorization (BOLA/IDOR)

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Setiap endpoint yang menerima ID (room id, conversation id, message id) memverifikasi bahwa objek itu benar milik user yang login - bukan hanya "objek ada" | `RoomService`, `AgentService` (percakapan), controller terkait |
| Belum | User biasa tidak bisa membaca/mengubah room atau percakapan milik user lain dengan menebak UUID | uji manual dengan dua akun berbeda |
| Belum | `PATCH` relay (`relayOn`) memeriksa kepemilikan/izin, bukan hanya validitas JWT | endpoint relay di `RoomService` |

## API2:2023 - Broken Authentication

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Sebagian | JWT dipakai untuk autentikasi | `AuthService`, `SecurityConfig` |
| Belum | Kekuatan hashing password (bcrypt/argon2, cost memadai) - bukan MD5/SHA polos/plaintext | `SecurityConfig` (`PasswordEncoder`) |
| Belum | Masa berlaku access token & refresh token wajar; refresh bisa dicabut saat logout | `AuthService` |
| Belum | Tidak ada endpoint sensitif yang terbuka tanpa autentikasi (`permitAll` berlebihan) | `SecurityConfig` |
| Belum | Percobaan login gagal tidak membocorkan "user ada/tidak ada" dan tidak bisa di-brute force tanpa batas | `AuthService`, `AuthController` |

## API3:2023 - Broken Object Property Level Authorization

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Request DTO tidak menerima field yang tidak seharusnya (mass assignment) - mis. `id`, `relayOn`, `role` dari body user | DTO di `data/request/` |
| Belum | Response tidak mengembalikan field internal (hash password, token internal, detail stack) | DTO di `data/response/` |

## API4:2023 - Unrestricted Resource Consumption

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Pagination & batas ukuran pada endpoint list (log, pesan percakapan, baris tabel ERD) | `LogController`, `AgentService`, `AdminErdDataService` |
| Belum | Batas panjang input pada body/parameter (prompt agent, nama room, filter) | validasi `@Size`/`@Valid` di DTO |
| Belum | Rate limit pada endpoint mahal (agent chat, quick-ask, transcribe, vision) | `AgentController`, `AgentService` |
| Belum | Upload dokumen dibatasi ukuran & tipe file | `DocumentService`, konfigurasi multipart |

## API5:2023 - Broken Function Level Authorization

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Endpoint admin (ERD admin, query log) hanya untuk role admin - dicek di server, bukan hanya disembunyikan di UI | `AdminErdDataService`, `SecurityConfig` |
| Belum | Tidak ada endpoint yang bergantung pada UI untuk menyembunyikan aksi berbahaya | seluruh controller |

## API6:2023 - Unrestricted Access to Sensitive Business Flows

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Aksi fisik (menyalakan relay, mengubah setelan perangkat) punya proteksi terhadap penyalahgunaan massal/otomatis | `RoomService.setRelay`, MQTT publisher |
| Belum | Perubahan state perangkat tercatat (audit trail: siapa, kapan, room mana) | log aplikasi / tabel audit |

## API7:2023 - Server Side Request Forgery (SSRF)

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | URL/parameter yang dipakai server untuk memanggil resource lain (Prometheus, InfluxDB, Groq, Grafana/Loki) tidak bisa dikendalikan user | `PrometheusService`, `InfluxDBService`, `LogController`, `GroqService` |
| Belum | Nama metrik di quick-ask divalidasi terhadap daftar metrik yang dikenal sebelum masuk ke query | `AgentService`, `PrometheusService` (guard injeksi PromQL) |

## API8:2023 - Security Misconfiguration

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | CORS dibatasi ke origin yang benar (bukan `*` dengan credentials) | `SecurityConfig` |
| Belum | Header keamanan aktif (HSTS, X-Content-Type-Options, CSP) | `SecurityConfig` / nginx frontend |
| Belum | Endpoint actuator tidak mengekspos informasi sensitif (`env`, `heapdump`, `beans`) | `application.properties` |
| Belum | Swagger/`/v3/api-docs` tidak terbuka di produksi tanpa proteksi | `SecurityConfig` |
| Belum | Detail error tidak mengembalikan stack trace ke klien | `@ControllerAdvice` / handler global |
| Belum | Kredensial tidak hardcoded di repo (compose, properties, script) | `docker-compose.yml`, `.env`, `.env.example` |

## API9:2023 - Improper Inventory Management

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Semua versi API terdokumentasi (tidak ada endpoint "lupa" yang masih hidup) | `/v3/api-docs` vs controller nyata |
| Belum | Pemetaan port jelas (8045 backend, 5173 frontend, 9090 Prometheus, 3000 Grafana, 9000 SonarQube) dan tidak ada yang terekspos ke internet tanpa sengaja | `docker-compose.yml` |

## API10:2023 - Unsafe Consumption of APIs

| Status | Yang perlu dicek | Cek di |
|---|---|---|
| Belum | Respons dari API pihak ketiga (Groq) divalidasi sebelum dipakai - tidak langsung dipercaya sebagai HTML/JS | `GroqService`, render markdown di frontend (`marked` + `dompurify`) |
| Belum | Timeout & penanganan error saat memanggil layanan eksternal | `GroqService`, `PrometheusService`, `InfluxDBService` |

---

## Urutan kerja yang disarankan

1. API1 + API2 + API5 - ini yang paling berisiko pada aplikasi IoT multi-user.
2. API8 - biasanya banyak temuan cepat (CORS, actuator, error detail).
3. API4 - tambahkan rate limit & pagination.
4. API7 + API10 - SSRF & konsumsi API luar.
5. API3 + API6 + API9 - mass assignment, proteksi aksi fisik, inventaris.
