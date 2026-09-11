# k6 Load Test — Powerbind Backend

Load test sisi yang dilayani backend ke browser: REST dashboard + WebSocket STOMP.
Tidak menargetkan jalur MQTT/ESP32 — untuk itu gunakan skrip Python paho terpisah
 jika dibutuhkan (data device hanya ±1 msg/s per unit, bukan titik rawan).

## Prasyarat

1. k6 terpasang: https://grafana.com/docs/k6/latest/set-up/install-k6/
   (Windows: `winget grafana.k6` atau `choco install k6`)
2. Backend jalan (default `http://localhost:8045`), PostgreSQL + InfluxDB + Mosquitto aktif.
3. Kredensial user test — **tanpa default**. Ditanyakan lewat prompt (password hidden)
   oleh runner `run-loadtest-admin.ps1` / `run-loadtest-user.ps1`.

## Menjalankan

```powershell
# Cara disarankan - kredensial ditanyakan lewat prompt:
.\run-loadtest-admin.ps1          # akun admin
.\run-loadtest-user.ps1           # akun family (role USER)

# Memanggil k6 langsung - kredensial WAJIB diberikan lewat -e:
k6 run -e K6_USERNAME={user} -e K6_PASSWORD={pw} perf/k6/dashboard-load.js
```

## Variabel

| Variabel | Default | Arti |
|---|---|---|
| `BASE_URL` | `http://localhost:8045` | Base URL backend |
| `WS_URL` | `ws://localhost:8045/ws` | Endpoint WebSocket STOMP |
| `VUS` | `20` | Jumlah virtual user puncak |
| `DURATION` | `1m` | Durasi plateau beban |
| `K6_USERNAME` / `K6_PASSWORD` | *(wajib — tanpa default)* | Kredensial akun yang diuji |

## Threshold (gerbang pass/fail)

- p95 latency REST dashboard < 500ms
- error rate HTTP < 1%
- 99% koneksi WS tersambung < 2 detik

Jika threshold gagal, k6 exit code != 0 — bisa dipakai di CI untuk mendeteksi
regresi performa.

## Output untuk laporan

```powershell
k6 run --summary-export=perf/k6/result.json perf/k6/dashboard-load.js
```

## Catatan penting

- Login hanya dilakukan **sekali di `setup()`** dan tokennya dipakai bersama
  semua VU — meniru realita browser (login sekali per sesi, dashboard di-refresh
  berkali-kali). Backend membatasi endpoint auth 10 req/60 s per IP
  (`RedisRateLimitFilter`) dan semua VU berasal dari satu IP yang sama, jadi
  login per-iterasi memicu 429 massal dan otomatis menggagalkan threshold.
- Endpoint `/ws` backend memakai **SockJS** — skrip meniru handshake-nya secara
  manual (GET `/ws/info`, lalu WS ke `/ws/{server}/{session}/websocket`, frame
  STOMP dibungkus array JSON) karena k6 hanya mendukung WebSocket native.
- JANGAN jalankan ke environment produksi / server dengan device ESP32 asli
  yang terhubung — data test akan mencemari dashboard dan database.
- Data yang ditulis ke InfluxDB selama test (bila ada) sebaiknya di-flush
  sebelum demo/pengambilan data skripsi.
