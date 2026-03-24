# Installation

Installing `kafka-gitops` requires either **Java** or **Docker**.

## Binary Release

Download the latest `kafka-gitops.zip` from the [GitHub releases page](https://github.com/chenrui333/kafka-gitops/releases), then extract the `kafka-gitops` executable and move it onto your `PATH`.

```bash
unzip kafka-gitops.zip
chmod +x kafka-gitops
mv kafka-gitops /usr/local/bin/kafka-gitops
```

Ensure the command is working by running:

```bash
kafka-gitops
```

You should see output similar to the following:

```text
Usage: kafka-gitops [-hvV] [--no-delete] [--skip-acls] [-c=<file>] [-f=<file>]
                    [COMMAND]
Manage Kafka resources with a desired state file.
  -c, --command-config=<file>
                      Command config properties file.
  -f, --file=<file>   Specify the desired state file.
  -h, --help          Display this help message.
      --no-delete     Disable the ability to delete resources.
      --skip-acls     Do not take ACLs into account during plans or applies.
  -v, --verbose       Show more detail during execution.
  -V, --version       Print the current version of this tool.
Commands:
  account   Create Confluent Cloud service accounts.
  apply     Apply changes to Kafka resources.
  plan      Generate an execution plan of changes to Kafka resources.
  validate  Validates the desired state file.
```

## Build From Source

The current build baseline uses JDK 21.

```bash
./gradlew buildRelease
```

The release zip is written to `build/distributions/kafka-gitops.zip`.

## Docker

We publish a Docker image at [`devshawn/kafka-gitops`](https://hub.docker.com/r/devshawn/kafka-gitops).

The Docker Hub namespace has not moved yet, so use that image name even though the source repository now lives under `chenrui333/kafka-gitops`.

The image runs as an unprivileged `kafka-gitops` user and uses `kafka-gitops` as the container entrypoint.

Example:

```bash
docker run --rm devshawn/kafka-gitops --version
```

If you need Amazon MSK IAM authentication, copy or mount the AWS MSK IAM auth plugin jar into the container and set `CLASSPATH` to that jar before running `kafka-gitops`.
