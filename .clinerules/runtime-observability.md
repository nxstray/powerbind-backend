---
paths:
  - "src/main/resources/**"
  - "docker-compose.yml"
  - "monitoring/**"
  - "mosquitto/**"
---

# Runtime, logging & metrics rules

Applies when touching configuration, logging, the compose stack or the
monitoring setup.

## Logging path

`logback-spring.xml` ships logs to Loki through the **loki4j** appender
(`com.github.loki4j.logback.Loki4jAppender`), pushing to
`${LOKI_URL}/loki/api/v1/push`. `LOKI_URL` comes from `loki.url` in
`application.properties` (`${LOKI_URL:http://localhost:3100}`) and must stay a
**base URL without a path** - `AdminLogController` appends
`/loki/api/v1/query_range` itself, the appender appends `/loki/api/v1/push`.

- Logback is parsed at startup: after any `logback-spring.xml` edit, restart the
  backend and verify before calling the work done.
- The stream label used across the stack is `app=powerbind-backend`; keep it,
  the admin log view and Grafana dashboards filter on it.
- Never log tokens, passwords, MQTT payloads with personal data, or full request
  bodies from the AI agent.

## Jackson 2 vs Jackson 3 (learned the hard way)

Spring Boot 4 moved its own internals to Jackson 3 (`tools.jackson`), but
`AdminLogController` and `PrometheusService` decode upstream responses with a
**Jackson 2** `com.fasterxml.jackson.databind.ObjectMapper`. Keep that split:

- Do not "modernise" those two to `tools.jackson` without a test proving the
  decode still works - the mismatch was a live bug, not a style choice.
- New JSON decoding outside those controllers should follow whatever the
  surrounding Spring-managed component already uses.

## Prometheus / metrics

`PrometheusService` is a thin proxy so the browser never reaches Prometheus.
Respect the invariants already there:

- metric and label values from users are sanitized against strict allow-patterns
  before entering a PromQL string; aggregation functions are whitelist-only
- responses are cached briefly so 30 s UI polling does not hammer Prometheus
- user-supplied text must never reach PromQL unfiltered

`prometheus.base-url` is `${PROMETHEUS_BASE_URL:http://localhost:9090}`; under
compose it must be `http://prometheus:9090`. Actuator exposes
`health,prometheus,metrics` with `management.metrics.tags.application=powerbind-backend`.

## Compose and services

`docker-compose.yml` defines `postgres`, `redis`, `mosquitto`, `influxdb`,
`loki`, `prometheus`, `grafana`, `sonarqube`, plus `backend`/`frontend`. Config
that belongs to them lives in `monitoring/prometheus/prometheus.yml`,
`monitoring/grafana/provisioning/**`, `monitoring/grafana/dashboards/**` and
`mosquitto/mosquitto.conf`.

- Assume the stack is already running; verify before starting anything, and
  never `down -v` a volume to "fix" a connection problem.
- A Grafana login failure usually means the persisted volume holds an older
  admin password than the current env value. Report it - resetting Grafana
  credentials is destructive and needs explicit approval.
- In-container service names differ from host names (`loki` vs `localhost`).
  Anything added to the compose `backend` environment must use service names.

## Verification before closing out logging/metrics work

```powershell
Invoke-WebRequest http://localhost:8045/actuator/health          # UP
# POST /api/auth/login (admin), then with the bearer token:
# GET /api/admin/logs            -> rows from the app=powerbind-backend stream
# GET /api/admin/metrics/names   -> non-empty list
# GET /api/admin/metrics/query   -> series with points
```

Do not claim logs or metrics are healthy from the code alone; check the running
service. Generated reports (`zap/`, `sbom/`) are git-ignored - never force-add.
