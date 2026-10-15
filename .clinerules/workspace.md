# Powerbind backend - workspace rules

Always-on rules for this repository. Git discipline below is enforced by
`.clinerules/hooks/PreToolUse.ps1`. Conditional rules add detail per path:
`testing.md` (tests, `pom.xml`), `runtime-observability.md` (resources, compose,
monitoring).

## Facts about this repo

| Item | Value |
| --- | --- |
| Framework | Spring Boot **4.1.1** (`<parent>` in `pom.xml`) |
| Build | Maven, **no wrapper** - run `mvn` from the repo root (heap caps live in `.mvn/jvm.config`) |
| HTTP | `server.port=8045`, no context path; actuator exposes `health,prometheus,metrics` |
| Base package | `com.powerbind.backend` |
| Layers | `controller` -> `service` -> `repository`; DTOs in `data/request` + `data/response`; errors in `global` |
| Integrations | MQTT (presence/power), InfluxDB (time series), PostgreSQL, Redis, Loki, Prometheus, Groq |

## Commands

```powershell
mvn test                     # default suite; or .\run-test.ps1 (same, friendlier output)
mvn -Dtest=PrometheusServiceTest test     # one class, always start here
mvn -Dtest=PrometheusServiceTest#methodName test
mvn package -DskipTests
mvn spring-boot:run          # needs the env vars and services below
.\run-allure.ps1             # test run + Allure report
```

Chain commands with `;`, never `&&` - the shell here is Windows PowerShell.
Performance, UI (Selenium) and Groq-dependent tests are excluded by default
through `excludedGroups`; do not widen that list without being asked.

## Sumber kebenaran

- Konvensi kode (Java): skill `code-standards`.
- Perubahan sekecil mungkin, laporkan temuan di luar scope: skill `minimal-change-policy`.
- Secrets: ikuti skill `env-and-secrets`.
- Lanjutan sesi lama: baca `docs/progress.md` dulu, jangan explore ulang dari nol
  (protokolnya di `.clinerules/progress.md`).

## Pre-commit stack check

The sonar CI job and the live log/metrics checks run against **this machine**,
so before committing backend work these must be answering:

| Service | Where | How to verify |
| --- | --- | --- |
| SonarQube | container `powerbind-sonarqube` (127.0.0.1:9000) | `GET /api/system/status` -> `UP` (startup 1-3 min) |
| Loki | container `powerbind-loki` (127.0.0.1:3100) | `GET /ready` -> 200 |
| Prometheus | container `powerbind-prometheus` (127.0.0.1:9090) | `GET /-/healthy` |
| Grafana | container `powerbind-grafana` (127.0.0.1:3000) | `GET /api/health` |
| Redis, for `mvn test` | standalone container `redis` (6379:6379) | `docker start redis` when down |

Pitfalls that cost real time here:

- The standalone container is named `redis`. `powerbind-redis` is the compose
  service on **6380** and is not what the app or `mvn test` reads.
- `docker compose up -d` exposes shifted ports (5433/6380/1884/8087); the app
  reads the plain ones, served natively: postgres (Windows service
  `postgresql-x64-14`, 5432), mosquitto (Windows service, 1883), and influxd
  started by hand from an admin PowerShell (8086). No docker command starts
  those - report it to the user instead of guessing.
- `caddy` (80/443) is not needed for commit; its role is still unconfirmed.

## Commit discipline

Satu file per commit; bulk staging diblokir hook `PreToolUse.ps1`. Stage path
spesifik, jangan `git add .` / `-A` / `commit -a`. `docs/progress.md` gitignored
(local-only, never push): jangan di-stage/di-commit, hook juga memblokirnya. Format pesan
`type(scope): summary`, imperatif, tanpa titik. Commit hanya jika diminta. Sebelum commit, tampilkan SATU ringkasan batch di chat (tanpa ask_question per file): daftar file + `git diff --stat` + 1 kalimat isi tiap diff, diakhiri kalimat: bilang 'commit' jika sudah selesai reviewnya. Tunggu kata `commit` eksplisit sebelum `git add`/`commit` apapun; satu persetujuan hanya untuk batch yang diringkas itu.

## Done means

Targeted test class passes, then the affected suite, then `mvn test` when the
change is shared. If the task touched logging or metrics, the running service is
restarted and the Loki/Prometheus-backed admin endpoints are checked live.
`git status` ends clean and no build artifact was committed.
