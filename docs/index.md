# kafka-gitops

![kafka-gitops](https://i.imgur.com/jnDwYp8.png)

Manage Apache Kafka topics, services, and ACLs through a desired state file.

## Overview

Kafka GitOps is an Apache Kafka resources-as-code tool that automates Kafka topic and ACL management from version-controlled state files.

Topics and services are defined in YAML. When run, `kafka-gitops` compares the desired state to the actual cluster state, generates a plan, and can apply the required changes.

The tool also generates the ACLs needed for common Kafka application types. By defining services, you can avoid manually managing large ACL sets for applications, Kafka Streams, and Kafka Connect clusters.

## Features

- Built for CI/CD pipelines that validate and apply Kafka changes from code.
- Declarative desired-state management for topics, services, users, and ACLs.
- Plan and apply workflows with idempotent execution.
- Bootstrap existing clusters into a valid starting state file with `import`.
- Support for self-hosted Kafka, managed Kafka, and Confluent Cloud.
- Continue-from-failure behavior: fix the issue, regenerate a plan, and resume safely.

## Compatibility

The Gradle build targets JDK 21 and `kafka-clients` 4.2.0.

Broker compatibility is validated against two local and CI fixtures:

- a Kafka 3.9-compatible ZooKeeper fixture
- a Kafka 4-compatible KRaft fixture

## Getting Started

Start with:

- [Installation](installation.md) for binary, source, and container usage.
- [Quick Start](quick-start.md) for a local walkthrough.
- [Specification](specification.md) for the desired state file format.

To bootstrap an existing cluster into a starting file, run `kafka-gitops import -o imported-state.yaml`. The generated file captures topics and raw ACLs as `users` plus `customUserAcls`; it does not infer higher-level service definitions.

## Configuration

Kafka client properties are configured through `KAFKA_*` environment variables.

Examples:

- `KAFKA_BOOTSTRAP_SERVERS` becomes `bootstrap.servers`
- `KAFKA_CLIENT_ID` becomes `client.id`

You can also provide a Kafka client properties file with `--command-config` or `-c`:

```bash
kafka-gitops -c command.properties -f state.yaml validate
```

Properties from the command config file are merged with `KAFKA_*` environment variables.
If `--command-config` is provided, the file must exist and be readable or the command exits before doing any validation, planning, or apply work.

If you use the username/password SASL shortcut, also set `KAFKA_SASL_MECHANISM` to a supported value such as `PLAIN`, `SCRAM-SHA-256`, or `SCRAM-SHA-512`.

For mechanisms such as Kerberos `GSSAPI`, skip the username/password shortcut and provide the native Kafka client settings directly, for example `KAFKA_SASL_MECHANISM=GSSAPI` plus `KAFKA_SASL_JAAS_CONFIG=...`.

### Amazon MSK IAM

`kafka-gitops` supports Amazon MSK clusters that use the `AWS_MSK_IAM` SASL mechanism when the AWS MSK IAM auth plugin jar is on the Java classpath.

Example:

```bash
export CLASSPATH=/path/to/aws-msk-iam-auth-<version>-all.jar
export KAFKA_BOOTSTRAP_SERVERS=b-1.example.amazonaws.com:9098
export KAFKA_SECURITY_PROTOCOL=SASL_SSL
export KAFKA_SASL_MECHANISM=AWS_MSK_IAM
export KAFKA_SASL_JAAS_CONFIG='software.amazon.msk.auth.iam.IAMLoginModule required;'
export KAFKA_SASL_CLIENT_CALLBACK_HANDLER_CLASS='software.amazon.msk.auth.iam.IAMClientCallbackHandler'

kafka-gitops -f state.yaml validate
```

The container image supports the same approach: copy or mount the AWS MSK IAM auth plugin jar into the container and set `CLASSPATH` so the launcher can load it.

## Operating Model

The intended workflow is:

- Store state files in version control.
- Propose topic, service, or ACL changes through pull requests.
- Validate the state file and generate a plan in CI.
- Review the plan, then apply it in a controlled environment.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](https://github.com/chenrui333/kafka-gitops/blob/main/CONTRIBUTING.md) for contribution guidelines.

## License

Copyright (c) 2020 Shawn Seymour.

Licensed under the [Apache 2.0 license](https://github.com/chenrui333/kafka-gitops/blob/main/LICENSE).
