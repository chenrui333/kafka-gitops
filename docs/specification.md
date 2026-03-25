# Desired State Specification

This document describes how to write a Kafka cluster desired state file. The file format is YAML.

!!! note

    For the current packaged CLI release, see the repository releases page.

The desired state file consists of:

- **Settings** [Optional]: Specific settings for configuring `kafka-gitops`.
- **Schemas** [Optional]: Schema Registry subject definitions.
- **Topics** [Optional]: Topic and topic configuration definitions.
- **Services** [Optional]: Service definitions for generating ACLs.
- **Users** [Optional]: User definitions for generating ACLs.
- **Custom Service ACLs** [Optional]: Definitions for custom, non-generated ACLs.
- **Custom User ACLs** [Optional]: Definitions for custom, non-generated ACLs.

## Settings

**Synopsis**: These are specific settings for configuring `kafka-gitops`.

**Options**:

- **ccloud** [Optional]: An object that contains an `enabled` field. Set this to `true` when using a Confluent Cloud cluster.
- **topics** [Optional]:
  - **defaults** [Optional]: Specify topic defaults so you do not need to repeat them for every topic. `partitions` and `replication` are supported.
  - **blacklist** [Optional]: Add a prefixed topic blacklist for ignoring specific topics when using `kafka-gitops`. This allows topics to be ignored from deletion if they are not defined in the desired state file.
  - **whitelist** [Optional]: Add a prefixed topic whitelist to limit management to specific topic prefixes. This is mutually exclusive with `blacklist`, and all topics defined in the state file must match one of the whitelisted prefixes.
- **schemaRegistry** [Optional]:
  - **url** [Required when `schemas` are defined]: Base URL for the Schema Registry API.
  - **username** [Optional]: Basic-auth username for Schema Registry.
  - **password** [Optional]: Basic-auth password for Schema Registry. If either `username` or `password` is set, both must be provided.

**Example**:
```yaml
settings:
  ccloud:
    enabled: true
  schemaRegistry:
    url: http://localhost:8081
  topics:
    defaults:
      partitions: 6
      replication: 3
    whitelist:
      prefixed:
        - team-a.
```

### Environment-Specific State

`kafka-gitops` does not implement built-in overlay merge semantics across multiple full state files.

Supported composition patterns are:

- use `settings.files` to split `services`, `topics`, and `users` into separate source files
- generate one final state file per environment outside `kafka-gitops`, then run `plan` or `apply` against that file

For example, you can compose shared and environment-specific YAML before invoking the CLI:

```bash
yq eval-all '. as $item ireduce ({}; . * $item )' state.base.yaml state.dev.yaml > state.generated.dev.yaml
kafka-gitops -f state.generated.dev.yaml plan
```

## Schemas

**Synopsis**: Define Schema Registry schemas and the subjects they should be registered under.

Schema management is currently add/update only. `kafka-gitops` compares the desired schema to the latest version of each subject and registers a new version when the schema text, type, or references differ. It does not delete subjects or older versions.

Each schema definition must include exactly one of:

- `relativeLocation`: a schema file path relative to the main state file
- `schema`: inline schema text

`type` defaults to `AVRO`. Supported values are `AVRO`, `JSON`, and `PROTOBUF`.

If `subjects` is omitted, `kafka-gitops` defaults it to `<schema-name>-value`.

**Example**:

```yaml
settings:
  schemaRegistry:
    url: http://localhost:8081
    username: schema-user
    password: schema-pass

schemas:
  personnel:
    relativeLocation: schemas/personnel.avsc
    subjects:
      - personnel-raw-value
      - personnel-refined-value
    references:
      - name: common.avsc
        subject: common-value
        version: 3
```

## Topics

**Synopsis**: Define the topics you would like on your cluster and their configuration.

!!! note

    Each topic is defined as a key-value pair, with the key being the topic name and the value being an object of settings.

**Example**:

```yaml
topics:
  my-topic-name:
    partitions: 6
    replication: 3
    configs:
      cleanup.policy: compact
      segment.bytes: 1000000
```

If a default `replication` value is supplied in the `settings` block, then the `replication` field can be omitted. If a default `replication` value is provided and the `replication` field in the topic definition is set, the default will be overridden for that topic.

If a default `partitions` value is supplied in the `settings` block, then the `partitions` field can also be omitted. If a default `partitions` value is provided and the `partitions` field in the topic definition is set, the default will be overridden for that topic.

## Services

**Synopsis**: Define the services that will utilize your Kafka cluster. These service definitions allow `kafka-gitops` to generate ACLs for you. Yay!

!!! note

    Each service has a `type`. This defines its structure.

There are currently three service types:

- `application`
- `kafka-connect`
- `kafka-streams`

!!! note

    If you use Confluent Cloud, omit the `principal` fields.

**Example application**:

!!! note

    The `group-id` property is optional and defaults to the service name.

```yaml
services:
  my-application-name:
    type: application
    principal: User:my-application-principal
    group-id: optional-group-id-override
    produces:
      - topic-name-one
    consumes:
      - topic-name-two
      - topic-name-three
```

**Example kafka connect cluster**:

!!! note

    The `group-id` property is optional and defaults to the service name. The `storage-topics` property is also optional; the defaults are documented on the [Services](services.md) page.

```yaml
services:
  my-kafka-connect-cluster-name:
    type: kafka-connect
    principal: User:my-connect-principal
    group-id: optional-group-id-override
    storage-topics:
      config: optional-custom-config-topic
      offset: optional-custom-offset-topic
      status: optional-custom-status-topic
    connectors:
      my-source-connector-name:
        produces:
          - topic-name-one
      my-sink-connector-name:
        consumes:
          - topic-name-two
```

**Example kafka streams application**:

!!! note

    The `application-id` property is optional and defaults to the service name.

```yaml
services:
  my-kafka-streams-name:
    type: kafka-streams
    principal: User:my-streams-principal
    application-id: optional-application-id-override
    produces:
      - topic-name-one
    consumes:
      - topic-name-two
```

Behind the scenes, `kafka-gitops` generates ACLs based on these definitions.

## Users

**Synopsis**: Define the users that will utilize your Kafka cluster. These user definitions allow `kafka-gitops` to generate ACLs for you. Yay!

!!! note

    If you use Confluent Cloud, users are service accounts prefixed with `user-`.

```yaml
users:
  my-user:
    principal: User:my-user
    roles:
      - writer
      - reader
      - operator
```

Currently, three predefined roles exist:

- **writer**: access to write to all topics
- **reader**: access to read all topics using any consumer group
- **operator**: access to view topics, topic configs, and to read topics and move their offsets

Outside of these very simple roles, you can define custom ACLs per-user by using the `customUserAcls` block.


## Custom Service ACLs

**Synopsis**: Define custom ACLs for a specific service. 

For example, if a specific application needs to produce to all topics prefixed with `kafka.` and `service.`, you may not want to define them all in your desired state file. 

If you have a service `my-test-service` defined, you can define custom ACLs as so:

```yaml
customServiceAcls:
  my-test-service:
    read-all-kafka:
      name: kafka.
      type: TOPIC
      pattern: PREFIXED
      host: "*"
      principal: User:my-test-service
      operation: READ
      permission: ALLOW
    read-all-service:
      name: service.
      type: TOPIC
      pattern: PREFIXED
      host: "*"
      principal: User:my-test-service
      operation: READ
      permission: ALLOW
```

!!! note

    When using Confluent Cloud, the `customServiceAcls` key must still match a service defined under `services`, and that service name must match the existing Confluent Cloud service account name. The `principal` value in the custom ACL is ignored and replaced with the live service-account ID.

## Custom User ACLs

**Synopsis**: Define custom ACLs for a specific user. 

For example, if a specific user needs to produce to all topics prefixed with `kafka.` and `service.`, you may not want to define them all in your desired state file. 

If you have a user `my-test-user` defined, you can define custom ACLs as so:

```yaml
customUserAcls:
  my-test-user:
    read-all-kafka:
      name: kafka.
      type: TOPIC
      pattern: PREFIXED
      host: "*"
      operation: READ
      permission: ALLOW
```

!!! note

    The `principal` field can be omitted here and will be inherited from the user definition.
