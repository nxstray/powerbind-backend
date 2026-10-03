# Powerbind backend - workspace rules

Always-on rules for this repository. The git rules in "Commit discipline" below
are enforced automatically by `.clinerules/hooks/PreToolUse.ps1`. Two
conditional rules add detail for specific paths: `testing.md` (tests, `pom.xml`)
and `runtime-observability.md` (resources, compose, monitoring).

## Facts about this repo

| Item | Value |
| --- | --- |
| Framework | Spring Boot **4.1.1** (`<parent>` in `pom.xml`; `README.md` still says 3.4.5 and is not the source of truth) |
| Language | Java 17 (`<java.version>`) |
| Build | Maven, **no wrapper** - run `mvn` from the repo root (heap caps live in `.mvn/jvm.config`) |
| HTTP | `server.port=8045`, no context path; actuator exposes `health,prometheus,metrics` |
| Base package | `com.powerbind.backend` |
| Layers | `controller` -> `service` -> `repository`; DTOs in `data/request` + `data/response`; errors in `global` |
| Migrations | Flyway in `src/main/resources/db/migration`, latest is `V10__add_role_to_users.sql` |
| Integrations | MQTT (presence/power), InfluxDB (time series), PostgreSQL, Redis, Loki, Prometheus, Groq |

## Commands

```powershell
mvn test                     # default suite; or .\run-test.ps1 (same, friendlier output)
mvn -Dtest=PrometheusServiceTest test     # one class, always start here
mvn -Dtest=PrometheusServiceTest#methodName test
mvn clean package -DskipTests
mvn spring-boot:run          # needs the env vars and services below
.\run-allure.ps1             # test run + Allure report
```

Chain commands with `;`, never `&&` - the shell here is Windows PowerShell.
Performance, UI (Selenium) and Groq-dependent tests are excluded by default
through `excludedGroups`; do not widen that list without being asked.

## Working rules

1. Read the current version of a file before editing it. Make the smallest
   change that solves the request; no drive-by refactors, renames or reformat.
2. Keep the layer split: controllers only delegate and carry **no business
   logic**. Every endpoint returns `ResponseEntity<ApiResponse<T>>`
   (`data/ApiResponse.java`) - never a raw object or bare `ResponseEntity<T>`.
3. Resolve the caller with `@AuthenticationPrincipal String username` and look
   up the `User` entity in the service layer via the repository. Auth logic does
   not belong in a controller.
4. New DTOs are nested static classes inside the existing holder
   (`AuthRequest.Login`, `AuthResponse.Profile`), not one file per action.
5. Use Lombok the way the surrounding class does; do not hand-write
   constructors, getters or setters.
6. Failures are thrown (`IllegalArgumentException`, `ResourceNotFoundException`,
   `AccountLockedException`) and translated centrally in
   `global/GlobalExceptionHandler`. No ad-hoc try/catch that returns a custom body.
7. A schema change means a **new** `V{n+1}__description.sql`. Never edit a
   migration that has already run.
8. Do not add dependencies or new abstractions when existing code can do the
   job. Spring Boot 4 already brings what is needed for JSON, HTTP and metrics.
9. Anything found wrong outside the current scope is reported, not silently fixed.

## Secrets and environment

- Every credential goes through `.env` and `${VAR_NAME}` in
  `application.properties` - never hardcoded in Java, SQL, tests or logs.
- New properties need a safe default (`${VAR:default}`) so startup cannot crash
  on an unset env var, and a clear warning in the seeding path (see
  `DataInitializer`) when the value is missing.
- `app.*` properties must be described in
  `src/main/resources/META-INF/spring-configuration-metadata.json`.
- Family passwords exist only hashed in the database; no endpoint returns a
  plaintext password and nothing logs one.
- Variables in play: `JWT_SECRET`, `DB_USERNAME`, `DB_PASSWORD`, `GROQ_API_KEY`,
  `INFLUXDB_URL`/`INFLUXDB_TOKEN`/`INFLUXDB_ORG`/`INFLUXDB_BUCKET`, `LOKI_URL`,
  `PROMETHEUS_BASE_URL`, `MQTT_BROKER_URL`, `REDIS_HOST`/`REDIS_PORT`,
  `CORS_ALLOWED_ORIGINS`, `APP_FAMILY_USERS`, `APP_DEFAULT_USER_USERNAME`/`PASSWORD`.
  Never paste their values into chat, commits or the README.

## Runtime expectations

- The stack (`postgres`, `redis`, `mosquitto`, `influxdb`, `loki`, `prometheus`,
  `grafana`, `sonarqube`, plus `backend`/`frontend`) is usually already up from
  `docker-compose.yml`, sometimes as native services. Check first
  (`docker compose ps`, startup log, `/actuator/health`) and never start a
  container as a side effect of a task.
- `GET /actuator/health` is the liveness check. Admin verification endpoints:
  `POST /api/auth/login`, then `GET /api/admin/logs`,
  `GET /api/admin/metrics/names`, `GET /api/admin/metrics/query`.

## Commit discipline (hook-enforced)

One file per commit. These are blocked by `PreToolUse.ps1` and will fail:

- `git add .`, `git add -A`, `git add --all`
- `git commit -a`, `git commit -am <msg>`, `git commit --all`, `git commit .`
- any `git commit` while **more than one file** is staged

The guard counts `git diff --cached --name-only`, so a pathspec does not bypass
it - `git commit -m "..." -- path/to/File.java` is still blocked while the index
holds two files. Work strictly one file at a time:

```powershell
git add src/main/java/com/powerbind/backend/service/PrometheusService.java
git commit -m "fix(metrics): decode Prometheus responses with a Jackson 2 ObjectMapper"
git status --short          # index must be empty before the next add
```

- Message format: `type(scope): summary`, imperative, no trailing period.
- Show the user the summary the hook writes to `logs/commit-log.txt`, then wait for
  the user's approval before running the commit. The hook only injects the summary
  as context - it does not block a valid single-file commit, so the pause is ours.
- Approval covers one commit only. After it, stage, commit and check that one file,
  then show the summary again before the next file.
- The `git-workflows` skill says to run `git add .`. The hook is stricter and
  wins: always stage the explicit path.
- Hook logs under `.clinerules/hooks/logs/` stay untracked (nested `.gitignore`);
  the root `.gitignore` also ignores `logs/`, `*.log`, `target/`, `.allure`,
  `sbom/` and `zap/`.

## Done means

Targeted test class passes, then the affected suite, then `mvn test` when the
change is shared. If the task touched logging or metrics, the running service is
restarted and the Loki/Prometheus-backed admin endpoints are checked live.
`git status` ends clean and no build artifact was committed.
