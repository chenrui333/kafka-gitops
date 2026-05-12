# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — v0.5.2

### Fixed
- `KafkaService`: restore thread interrupt flag in all 10 `InterruptedException` catch sites — previously swallowed, breaking cooperative cancellation
- `KafkaService`: `updateTopicReplication` reuses existing `AdminClient` instead of opening a second connection
- `KafkaService`: add upfront validation for null/negative replication factor in `updateTopicReplication`
- `KafkaService`: fix `addTopicPartition` guard (`<= 1` → `< 1`)
- `ApplyManager`: skip `NO_CHANGE` topic config plans to prevent spurious `incrementalAlterConfigs` API calls on every apply
- `PlanManager`: fix `FileWriter` resource leak in `writePlanToFile` — use try-with-resources, remove redundant `createNewFile()`
- `PlanManager`: guard against empty partition list before `.get(0)` — throws `ValidationException` with context instead of `IndexOutOfBoundsException`
- `StateManager`: replace unsafe `orElseGet(defaultPartitions::get)` with `orElseThrow(ValidationException)` for missing defaults
- Fix typos in user-facing error messages: `increate` → `increase`, `enougth` → `enough`, `newReasignement` → `newReassignment`
- Make static loggers `final` in `PlanManager`, `KafkaService`, `StateManager`

### Tests
- Add unit tests for empty-partition `ValidationException`, invalid replication guard, and `addTopicPartition` guard
- Uncomment `cleanupSpec` in `PlanCommandIntegrationSpec` to prevent cluster state leaks between runs

## [0.5.1] - 2026-03-25

### Added
- Manage schema registry subjects from desired state

### Fixed
- Stabilize cluster cleanup between integration runs

### Changed
- Publish releases with a single `action-gh-release` step

## [0.5.0] - 2026-03-25

### Added
- Cluster import command
- Kafka broker compatibility matrix (3.x and 4.x)

### Fixed
- Make stale topic add plans idempotent
- Custom user ACLs planner regression

### Changed
- Pin action SHAs and switch to Renovate
- Port upstream topic planning improvements

## [0.4.0] - 2026-03-24

### Added
- Hardened CLI and config-loading path — invalid input, unreadable `--command-config` files, and incomplete SASL config fail fast
- Tightened validation for desired state: topic definitions require `partitions >= 1` and `replication >= 1`, unresolved custom ACL owners rejected pre-flight

### Fixed
- Topic replication decreases now properly rejected
- Kafka Streams internal topic handling with custom `application-id`
- Duplicate ACL generation
- Release packaging path for versioned shadow JAR

## [0.3.1] - 2026-03-23

See [0.3.0...0.3.1](https://github.com/chenrui333/kafka-gitops/compare/0.3.0...0.3.1)

## [0.3.0] - 2026-03-23

Initial tracked release.

[Unreleased]: https://github.com/chenrui333/kafka-gitops/compare/0.5.1...HEAD
[0.5.1]: https://github.com/chenrui333/kafka-gitops/compare/0.5.0...0.5.1
[0.5.0]: https://github.com/chenrui333/kafka-gitops/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/chenrui333/kafka-gitops/compare/0.3.1...0.4.0
[0.3.1]: https://github.com/chenrui333/kafka-gitops/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/chenrui333/kafka-gitops/releases/tag/0.3.0
