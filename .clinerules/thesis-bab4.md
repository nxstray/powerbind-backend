---
paths:
  - "src/main/java/com/powerbind/backend/service/MqttMessageHandler.java"
  - "src/main/java/com/powerbind/backend/service/InfluxDBService.java"
  - "src/main/java/com/powerbind/backend/service/RoomService.java"
  - "src/main/resources/logback-spring.xml"
  - "src/main/resources/application.properties"
  - "src/test/**/*.java"
  - "run-*.ps1"
---

# Thesis Bab 4 — backend evidence rules

Applies when doing work whose output will be quoted in thesis Chapter 4
(Hasil dan Pembahasan). Read together with `testing.md` (test layout and
commands) and `runtime-observability.md` (logging path, Loki/Prometheus
invariants). Needs context: skripsi note `C:\PlatformIO\Projects\writing\skripsi\docs\note-backend.md`
(items B-1..B-4), read-only — never write into `C:\PlatformIO\Projects\writing\**`
from this workspace.

## Evidence-first

Every number handed to the skripsi workspace must carry: run date (UTC+7),
commit hash (`git log --oneline -1`), exact command used, and infra state
(which of postgres/redis/mosquitto/influxdb were native vs compose).
Format: `total=X passed=Y failed=Z skipped=W, date, commit`.
Do not invent accuracy/latency/stability numbers — physical-test data belongs
to the firmware workspace (W-1..W-3); backend only supplies supporting
timestamps and queries.

## Logging during physical tests (B-1)

`MqttMessageHandler` receives on `log.debug` (`[MQTT] Received on topic ...`),
but `logback-spring.xml` root is `INFO` — receive timestamps are invisible by
default. Prefer the no-code-change option first:

```powershell
$env:LOGGING_LEVEL_COM_POWERBIND_BACKEND_SERVICE_MQTTMESSAGEHANDLER="DEBUG"
mvn spring-boot:run
```

Only add a permanent `INFO` receive-timestamp line if W-2 explicitly needs a
`t_backend` column and the env override proves insufficient. Any such line
must log topic + timestamp only — never tokens, passwords, MQTT payloads with
personal data, or full AI-agent request bodies. After any `logback-spring.xml`
edit, restart the backend and verify live (see `runtime-observability.md`).

## InfluxDB evidence queries (B-1/B-4)

Time-series truth lives in InfluxDB (`powerbind`/`smarthome`, measurements
`presence` + `power`). Reuse the sanitizers already in `InfluxDBService`
(`sanitizeHours`, `sanitizeTag`); user-supplied text must never reach a Flux
string unfiltered. Evidence samples are capped at 5 rows per measurement and
must be sanitized (no secrets, no personal data). A dedicated
`queryPresenceHistory` method may only be added on explicit user request —
default to ad-hoc Flux during the test session.

## Credentials for reproducible runs (B-3)

Selenium (`SeleniumTestBase`) skips fast via `Assumptions` when
`-Dselenium.username/password` or `-Ddb.username/password` are missing.
Rules:

- Never commit credentials; never invent test accounts the user did not
  approve. Follow the `env-and-secrets` skill.
- If the user withholds credentials, do NOT stall the whole Bab 4 handoff:
  run everything credential-free (`mvn test` default suite + `run-allure.ps1`),
  and report the `ui` group as `SKIPPED (assumptions, no credentials)` with
  the exact properties missing, so the skripsi side can write that honestly.
- The `must_change_password` DB toggle needs `-Ddb.username/password`
  (`SeleniumTestBase.setMustChangePassword`); without it, suites hitting the
  mandatory modal stay skipped — report, don't bypass via new endpoints.

## Test reruns for quotable results (B-2)

Order: one class first (`mvn -Dtest=PrometheusServiceTest test`), then the
default suite (`mvn test` or `.\run-test.ps1`, excluded groups untouched),
then `.\run-allure.ps1`. The `ui`/`performance`/`requires-groq` groups stay
excluded unless the user explicitly provides what's needed (browser,
credentials, Groq key). Record totals + date + commit hash; report staleness
honestly when `target/surefire-reports` predates the current HEAD.
