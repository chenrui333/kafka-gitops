# CLAUDE.md

## What This Project Is

A CLI tool that manages Apache Kafka topics and ACLs through a GitOps workflow. Users declare desired state in YAML files; the tool plans and applies changes to a Kafka cluster (similar to Terraform for Kafka).

Commands: `plan`, `apply`, `import`, `validate`.

## Build & Run

- **Language**: Java 21, Groovy 4.0 for tests
- **Build**: `./gradlew build` (compiles, tests, shadow jar)
- **Test**: `./gradlew test`
- **Unit tests only**: `./gradlew test --tests "com.devshawn.kafka.gitops.manager.*"`
- **Single spec**: `./gradlew test --tests "com.devshawn.kafka.gitops.manager.PlanManagerSpec"`
- **Shadow jar**: `./gradlew shadowJar` → `build/libs/kafka-gitops-all.jar`
- **Coverage**: `./gradlew jacocoTestReport` → `build/reports/jacoco/`

## Test Infrastructure

Integration tests require a running Kafka cluster. CI uses Docker Compose files in `docker/`:
- `docker/docker-compose.yml` — Kafka 3.9
- `docker/docker-compose.kafka4.yml` — Kafka 4.0

Environment variables (defaults in `build.gradle`):
- `KAFKA_BOOTSTRAP_SERVERS` (default: `localhost:9092`)
- `KAFKA_SASL_JAAS_USERNAME` (default: `test`)
- `KAFKA_SASL_JAAS_PASSWORD` (default: `test-secret`)
- `KAFKA_SASL_MECHANISM` (default: `PLAIN`)
- `KAFKA_SECURITY_PROTOCOL` (default: `SASL_PLAINTEXT`)

To run integration tests locally: `docker compose -f docker/docker-compose.yml up -d` then `./gradlew test`.

## Test Patterns

- **Framework**: Spock 2.4 with `@Unroll` for parameterized tests
- **Integration specs** (`src/test/groovy/.../`): Run full CLI commands against a Kafka cluster. Use `TestUtils.seedCluster()` / `cleanUpCluster()` for setup. Fixtures live in `src/test/resources/plans/` as YAML input + JSON expected plan + TXT expected output triples.
- **Unit specs** (`src/test/groovy/.../manager/`): Stub `KafkaService` with anonymous subclass overrides. No Kafka cluster needed.
- **Adding a new integration test case**: Create `foo.yaml` + `foo-plan.json` in `src/test/resources/plans/`, add `"foo"` to the `where:` block in the relevant spec.

## Key Source Layout

```
src/main/java/com/devshawn/kafka/gitops/
├── MainCommand.java          # CLI entry point (picocli)
├── StateManager.java         # Orchestrates plan/apply/import/validate
├── manager/
│   ├── PlanManager.java      # Diff desired vs actual state
│   └── ApplyManager.java     # Apply plan to Kafka cluster
├── service/
│   └── KafkaService.java     # Kafka AdminClient wrapper
├── domain/
│   ├── plan/                 # TopicPlan, TopicConfigPlan, AclPlan, DesiredPlan
│   └── state/                # TopicDetails, DesiredState, AclDetails
└── config/                   # KafkaGitopsConfig, ManagerConfig

src/test/groovy/com/devshawn/kafka/gitops/
├── TestUtils.groovy                    # Cluster seed/cleanup helpers
├── PlanCommandIntegrationSpec.groovy   # Plan command integration tests
├── ApplyCommandIntegrationSpec.groovy  # Apply command integration tests
├── ImportCommandIntegrationSpec.groovy # Import command tests
├── ValidateCommandIntegrationSpec.groovy
└── manager/
    ├── PlanManagerSpec.groovy          # PlanManager unit tests
    └── ApplyManagerSpec.groovy         # ApplyManager unit tests
```

## Domain Model

- Topic configs are generic `Map<String, String>` — no special handling for any config key. Validation happens at the Kafka broker level.
- Immutable domain objects use FreeBuilder (`@FreeBuilder` annotation, generated `*_Builder` classes).
- Plan actions: `ADD`, `UPDATE`, `REMOVE`, `NO_CHANGE`.
- `PlanManager.planTopicConfigurations()` compares desired configs vs current `DYNAMIC_TOPIC_CONFIG` entries and generates config plans. Configs not in desired state but present as dynamic overrides are planned for `REMOVE`.
