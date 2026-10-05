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
  (protokolnya di `progress.md`).

## Commit discipline

Satu file per commit; bulk staging diblokir hook `PreToolUse.ps1`. Stage path
spesifik, jangan `git add .` / `-A` / `commit -a`. Format pesan
`type(scope): summary`, imperatif, tanpa titik. Commit hanya jika diminta.

## Done means

Targeted test class passes, then the affected suite, then `mvn test` when the
change is shared. If the task touched logging or metrics, the running service is
restarted and the Loki/Prometheus-backed admin endpoints are checked live.
`git status` ends clean and no build artifact was committed.
