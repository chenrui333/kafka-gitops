# AGENTS

## Scope

This repository contains the `kafka-gitops` CLI for managing Kafka topics, ACLs, and Confluent Cloud service-account workflows from desired-state YAML.

Keep changes small, test-backed, and scoped to one behavior or maintenance concern per PR unless the user explicitly asks for a combined batch.

## Toolchain

- Java: JDK 21
- Build: Gradle wrapper (`./gradlew`)
- Compatibility baseline: `kafka-clients` 4.2.0, validated against Kafka 3.9-compatible and Kafka 4-compatible broker fixtures
- Local Kafka fixture:
  - Kafka 3.9 broker lane: `docker compose -f docker/docker-compose.yml up -d`
  - Kafka 4 broker lane: `docker compose -f docker/docker-compose.kafka4.yml up -d`

## Repository Layout

- `src/main/java/com/devshawn/kafka/gitops/`
  - `MainCommand.java`: root CLI wiring
  - `cli/`: subcommands
  - `config/`: Kafka client config loading and manager config
  - `manager/`: plan/apply orchestration
  - `service/`: Kafka, parser, role, and Confluent Cloud integrations
  - `domain/`: desired state and plan models
- `src/test/groovy/com/devshawn/kafka/gitops/`: Spock coverage
- `src/test/resources/plans/`: YAML/JSON fixtures for CLI and planner tests
- `docs/`: end-user documentation
- `examples/`: sample desired-state layouts
- `.github/workflows/`: CI, docs, and release automation

## Common Commands

- Run the full test suite:
  - `./gradlew test`
- Run a targeted test class:
  - `./gradlew test --tests com.devshawn.kafka.gitops.PlanCommandIntegrationSpec`
- Run the CLI locally from source:
  - `./gradlew -q run --args='-f src/test/resources/plans/simple.yaml validate'`
- Build the release zip locally:
  - `./gradlew buildRelease`
- Build the release zip for a tagged version:
  - `./gradlew -PreleaseVersion=<tag> buildRelease`

## Repo-Specific Conventions

- Root CLI options such as `-f` and `-c` belong before the subcommand.
- Keep invalid CLI invocations non-zero so scripts and CI can reliably fail on bad arguments.
- Keep docs and examples aligned with the real CLI surface in `MainCommand.java`.
- Do not hardcode release versions in source or docs when build metadata can be used instead.
- Treat Kafka client config as sensitive. Do not log secret-bearing values such as JAAS or password fields.
- Treat an explicit `--command-config` path as required input: unreadable files should fail fast rather than falling back to ambient environment config.
- When changing planning or apply behavior, update the matching JSON/YAML fixtures and add focused regression coverage in Spock.
- Deduplicate generated ACLs in desired-state construction when different generation paths can produce the same binding.
- Use unique temp files in tests instead of shared `/tmp/plan.json` paths so repeated and parallel runs do not collide.
- Keep dependency review automation in place; if you change the Gradle or Actions surface, update `.github/dependabot.yml` deliberately rather than letting it drift.
- Integration-style tests assume the local Kafka fixture is running and may mutate cluster state; use `TestUtils.cleanUpCluster()` / `seedCluster()` patterns consistently.

## Release Notes

- The release workflow resolves the version from the tag or workflow input and passes it into Gradle.
- `--version` should reflect build metadata, not a hand-maintained string literal.
- If docs mention a current version, make sure that value is generated or removed so it cannot drift from releases.
- When preparing a release, prefer adding `release-notes/<version>.md`; the workflow uses that file for a richer release body and falls back to a generated commit summary only when no curated notes file exists.
