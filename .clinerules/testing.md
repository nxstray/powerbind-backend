---
paths:
  - "src/test/**/*.java"
  - "src/test/resources/**"
  - "pom.xml"
---

# Backend testing rules

Applies when touching test sources or the Maven test configuration.

## Layout

| Directory | Kind | Notes |
| --- | --- | --- |
| `src/test/java/.../unit` | Mockito unit tests | mock every repository and external client |
| `.../functional` | `@SpringBootTest(webEnvironment = RANDOM_PORT)` + RestAssured | real HTTP against the test context |
| `.../smoke` | startup / health checks | |
| `.../performance` | tagged `performance` | excluded by default |
| `.../selenium` | UI tests, tagged `ui` | excluded by default |
| `.../cucumber/runner` + `.../cucumber/steps` | BDD | features in `src/test/resources/cucumber/*.feature` |

Surefire only picks up `**/*Test.java`, `**/*Tests.java`, `**/*Runner.java`
(`pom.xml`). A file outside those patterns silently never runs - name it right or
extend the pattern deliberately.

## Excluded groups

`pom.xml` sets `<excludedGroups>requires-groq,performance,ui</excludedGroups>`.
Do not widen or narrow this without being asked; a Groq key or a browser must
not become a requirement for `mvn test`.

## Test profile

`src/test/resources/application-test.properties` gives H2 in-memory
(`MODE=PostgreSQL;NON_KEYWORDS=VALUE`), `ddl-auto=create-drop`, Flyway disabled,
`server.port=0`, and excludes the Spring Integration (MQTT) and Redis
auto-configurations. So:

- Never point a test at the real PostgreSQL, InfluxDB or MQTT broker.
- Schema in tests comes from the JPA entities, not from the Flyway scripts - a
  test that depends on a migration's seed data is wrong.
- `JWT_SECRET` still has to resolve; use a fixed dummy value inside the test,
  never a real secret.
- Cucumber steps may `@Autowired` repository + encoder to seed data directly. Do
  not call `/register` through HTTP for setup - that endpoint no longer exists.

## Commands

```powershell
mvn -Dtest=PrometheusServiceTest test          # start here, one class
mvn -Dtest=SomeTest#someMethod test            # one method
mvn test                                        # default suite
.\run-test.ps1                                  # mvn test + readable summary
.\run-allure.ps1                                # full run + Allure report
```

The Allure wrapper is **`run-allure.ps1`** - the skill's `run-allure-tests.ps1`
does not exist in this repo. Performance scripts are separate:
`run-performance-test.ps1`, `run-loadtest-user.ps1`, `run-loadtest-admin.ps1`,
with k6 scripts under `perf/k6`.

## Writing the test

1. Copy the conventions of the neighbouring test in the same package before
   inventing structure or naming.
2. Unit tests assert behaviour through the service, not through private fields;
   stub collaborators with Mockito and keep one logical assertion group per case.
3. Error paths matter here: exceptions are the contract, so assert the exception
   type and the shape `GlobalExceptionHandler` produces.
4. External HTTP clients (Loki, Prometheus, Groq) are mocked at the client
   boundary; no test opens a socket to `localhost:3100` or `:9090`.
5. Run the targeted class first, then the affected package, then `mvn test` if
   the change is shared. Report failures honestly, including ones caused by
   pre-existing staleness you did not touch.
