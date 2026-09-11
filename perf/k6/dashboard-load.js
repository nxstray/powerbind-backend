// k6 load test — Powerbind backend (REST + WebSocket STOMP)
//
// Simulasi N virtual user yang membuka dashboard secara bersamaan:
//   1. POST /api/auth/login                → ambil JWT (SEKALI di setup(), token dibagi ke semua VU)
//   2. GET  /api/dashboard/summary         → berat (JPA + query InfluxDB)
//   3. GET  /api/dashboard/power-history   → query agregasi InfluxDB 15m
//   4. WS   /ws (STOMP via SockJS) subscribe /topic/power → koneksi real-time fan-out
//
// Kenapa login hanya SEKALI: backend membatasi endpoint auth lewat
// RedisRateLimitFilter (10 req/60 s per IP) dan semua VU berasal dari satu IP
// yang sama (127.0.0.1). Login per-iterasi (versi lama) menabrak limit itu →
// ~99% login kena 429 → threshold http_req_failed selalu gagal. Realita
// browser juga begitu: login sekali per sesi, dashboard di-refresh berkali-kali.
//
// Catatan WebSocket: endpoint /ws backend memakai SockJS (.withSockJS() di
// WebSocketConfig) — handshake WebSocket polos ke /ws tidak pernah dapat 101.
// k6 hanya punya WebSocket native, jadi protokol SockJS ditiru manual:
// GET /ws/info → ws.connect /ws/{server}/{session}/websocket → server kirim
// frame 'o' → semua frame STOMP dibungkus array JSON, mis. ["CONNECT\n...\u0000"].
//
// Jalankan (backend harus sudah jalan, default http://localhost:8045):
//   k6 run perf/k6/dashboard-load.js
//   k6 run -e BASE_URL=http://localhost:8045 -e VUS=50 -e DURATION=2m perf/k6/dashboard-load.js
//
// Threshold pass/fail — dipakai sebagai gerbang regresi performa:
//   - p95 latency endpoint REST < 500ms
//   - error rate < 1%
//   - 99% koneksi WebSocket tersambung < 2 detik

import http from 'k6/http'
import ws from 'k6/ws'
import { check, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8045'
const WS_URL = __ENV.WS_URL || 'ws://localhost:8045/ws'
const VUS = parseInt(__ENV.VUS || '20', 10)
const DURATION = __ENV.DURATION || '1m'

const USERNAME = __ENV.K6_USERNAME || 'admin'
const PASSWORD = __ENV.K6_PASSWORD || 'admin123'

// Metrik tambahan di luar bawaan k6
const wsMessages = new Counter('stomp_messages_received')
const wsConnectTime = new Trend('ws_connect_time_ms')

// Penghitung sesi SockJS — menjamin session id unik untuk setiap koneksi
let wsSessionCounter = 0

export const options = {
  scenarios: {
    // Skenario 1: user membuka dashboard (login + REST)
    dashboard_users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: VUS }, // ramp-up
        { duration: DURATION, target: VUS }, // plateau
        { duration: '15s', target: 0 }, // ramp-down
      ],
      exec: 'dashboardScenario',
    },
    // Skenario 2: koneksi WebSocket STOMP yang menahan subscribe
    ws_users: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: Math.min(VUS, 20) },
        { duration: DURATION, target: Math.min(VUS, 20) },
        { duration: '15s', target: 0 },
      ],
      exec: 'wsScenario',
      startTime: '5s',
    },
  },
  thresholds: {
    'http_req_duration{scenario:dashboard_users}': ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
    'ws_connect_time_ms': ['p(99)<2000'],
  },
}

// Dipanggil HANYA dari setup() — sekali per run, bukan per iterasi/VU.
function login() {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } }
  )
  const ok = check(res, {
    'login 200': (r) => r.status === 200,
    'login returns accessToken': (r) => !!r.json('data.accessToken'),
  })
  if (!ok) return null
  return res.json('data.accessToken')
}

// setup() dijalankan sekali sebelum semua skenario dimulai; return-nya
// diteruskan ke setiap exec function sebagai argumen pertama (data.token).
// Kalau login di sini gagal, seluruh run di-abort — percuma lanjut tanpa JWT.
// JWT default exp 1 jam (application.properties), jauh di atas durasi test.
export function setup() {
  const token = login()
  if (!token) {
    throw new Error(
      'setup: login ke ' + BASE_URL + '/api/auth/login gagal — ' +
      'cek K6_USERNAME/K6_PASSWORD, atau tunggu rate limit auth reset (60 s).'
    )
  }
  return { token: token }
}

export function dashboardScenario(data) {
  const auth = { headers: { Authorization: `Bearer ${data.token}` } }

  const summary = http.get(`${BASE_URL}/api/dashboard/summary`, auth)
  check(summary, { 'summary 200': (r) => r.status === 200 })

  const history = http.get(`${BASE_URL}/api/dashboard/power-history?hours=24`, auth)
  check(history, { 'power-history 200': (r) => r.status === 200 })

  sleep(Math.random() * 3 + 2) // jeda antar-refresh menyerupai user nyata
}

// Skenario WS meniru handshake SockJS frontend Vue (server id 3 digit + session
// id unik; frame STOMP dibungkus array JSON setelah menerima frame 'o').
export function wsScenario() {
  const origin = { headers: { Origin: 'http://localhost:5173' } }
  const info = http.get(`${BASE_URL}/ws/info`, origin)
  const infoOk = check(info, {
    'sockjs info 200': (r) => r.status === 200 && r.json('websocket') === true,
  })
  if (!infoOk) {
    sleep(1)
    return
  }

  wsSessionCounter++
  const wsUrl =
    `${WS_URL}/000/${__VU}-${wsSessionCounter}-${Math.random().toString(36).slice(2, 8)}` +
    '/websocket'
  const connectStart = Date.now()
  const response = ws.connect(wsUrl, origin, function (socket) {
    socket.on('open', () => {
      wsConnectTime.add(Date.now() - connectStart)
    })
    socket.on('message', (msg) => {
      if (msg === 'o') {
        // Frame open SockJS → kirim STOMP CONNECT lalu SUBSCRIBE /topic/power —
        // meniru apa yang dilakukan frontend Vue (SimpMessagingTemplate fan-out).
        socket.send(
          JSON.stringify(['CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n\n\x00'])
        )
      } else if (msg.startsWith('a')) {
        // SockJS membungkus pesan server dalam array JSON: a["...","..."]
        for (const frame of JSON.parse(msg.slice(1))) {
          if (frame.startsWith('CONNECTED')) {
            socket.send(
              JSON.stringify(['SUBSCRIBE\nid:sub-0\ndestination:/topic/power\n\n\x00'])
            )
          }
          if (frame.startsWith('MESSAGE')) {
            wsMessages.add(1)
          }
        }
      }
    })
    socket.on('error', (e) => {
      console.error('WS error:', e)
    })
    // Tahan koneksi ~30 detik per VU iterasi
    socket.setTimeout(() => socket.close(), 30000)
  })
  const connected = check(response, { 'ws connected (101)': (r) => r && r.status === 101 })
  if (!connected) {
    // Handshake gagal — tidur supaya VU tidak mem-sweep backend dengan retry tanpa jeda
    sleep(1)
  }
}