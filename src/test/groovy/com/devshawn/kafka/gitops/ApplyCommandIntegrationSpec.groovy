package com.devshawn.kafka.gitops

import picocli.CommandLine
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

@Unroll
class ApplyCommandIntegrationSpec extends Specification {

    void setup() {
        TestUtils.cleanUpCluster()
    }

    void cleanupSpec() {
//        TestUtils.cleanUpCluster()
    }

    void 'test various successful applies - #planFile'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        String file = TestUtils.getResourceFilePath("plans/${planFile}-plan.json")
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute("-f", file, "apply", "-p", file)

        then:
        exitCode == 0
        out.toString() == TestUtils.getResourceFileContent("plans/${planFile}-apply-output.txt")

        cleanup:
        System.setOut(oldOut)

        where:
        planFile << [
                "simple",
                "application-service",
                "kafka-streams-service",
                "kafka-connect-service",
                "multi-file",
                "simple-users",
                "custom-service-acls",
                "custom-user-acls",
                "custom-group-id-application",
                "custom-group-id-connect",
                "custom-application-id-streams",
                "custom-storage-topic",
                "custom-storage-topics"
        ]
    }

    void 'test skip-acls flag'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        String file = TestUtils.getResourceFilePath("plans/${planFile}-plan.json")
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute("-f", file, "--skip-acls", "apply", "-p", file)

        then:
        exitCode == 0
        out.toString() == TestUtils.getResourceFileContent("plans/${planFile}-apply-output.txt")

        cleanup:
        System.setOut(oldOut)

        where:
        planFile << [
                "skip-acls-apply"
        ]
    }

    void 'test various valid applies with seed - #planFile #deleteDisabled'() {
        setup:
        TestUtils.seedCluster()
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        String file = TestUtils.getResourceFilePath("plans/${planFile}-plan.json")
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = -1
        if (deleteDisabled) {
            exitCode = cmd.execute("-f", file, "--no-delete", "apply", "-p", file)
        } else {
            exitCode = cmd.execute("-f", file, "apply", "-p", file)
        }

        then:
        exitCode == 0
        if (deleteDisabled) {
            assert out.toString() == TestUtils.getResourceFileContent("plans/${planFile}-no-delete-apply-output.txt")
        } else {
            assert out.toString() == TestUtils.getResourceFileContent("plans/${planFile}-apply-output.txt")
        }

        cleanup:
        System.setOut(oldOut)

        where:
        planFile                         | deleteDisabled
        "seed-topic-modification"        | true
        "seed-topic-modification"        | false
        "seed-topic-modification-3"      | true
        "seed-topic-modification-3"      | false
        "seed-topic-add-replicas"        | false
        "seed-topic-add-partitions"      | false
        "seed-acl-exists"                | false
        "no-changes"                     | false
        "no-changes-include-unchanged"   | false
    }

    void 'test reading missing file throws ReadPlanInputException'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)
        String file = TestUtils.getResourceFilePath("plans/simple.yaml")

        when:
        int exitCode = cmd.execute("-f", file, "apply", "-p", "null")

        then:
        exitCode == 2
        out.toString() == TestUtils.getResourceFileContent("plans/read-input-exception-output.txt")

        cleanup:
        System.setOut(oldOut)
    }

    void 'test reading invalid file throws ReadPlanInputException'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)
        String file = TestUtils.getResourceFilePath("plans/simple.yaml")
        String planFile = TestUtils.getResourceFilePath("plans/invalid-plan.json")

        when:
        int exitCode = cmd.execute("-f", file, "apply", "-p", planFile)

        then:
        exitCode == 2
        out.toString() == TestUtils.getResourceFileContent("plans/invalid-plan-output.txt")

        cleanup:
        System.setOut(oldOut)
    }

    void 'test apply decreases replication without error'() {
        setup:
        TestUtils.seedCluster()
        String file = TestUtils.getResourceFilePath('plans/seed-topic-remove-replicas-plan.json')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('-f', file, 'apply', '-p', file)

        then:
        exitCode == 0

        when:
        def topicDescriptions = TestUtils.withAdminClient { adminClient ->
            TestUtils.waitFor(adminClient.describeTopics(['topic-with-configs-1', 'topic-with-configs-2'] as Set).allTopicNames())
        }

        then:
        topicDescriptions['topic-with-configs-1'].partitions().every { it.replicas().size() == 1 }
        topicDescriptions['topic-with-configs-2'].partitions().every { it.replicas().size() == 1 }
    }

    void 'test apply sets retention.ms and retention.bytes on topic with no retention config'() {
        setup:
        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('retention-test-topic', 3, adminClient)
        }
        // Kafka 4.0 has slower topic/config propagation than 3.9;
        // poll for visibility before apply and after apply for config state.
        def conditions = new PollingConditions(timeout: 30, initialDelay: 1, factor: 1.25)
        conditions.eventually {
            assert TestUtils.getTopics().contains('retention-test-topic')
        }
        String planFile = TestUtils.getResourceFilePath('plans/retention-apply-set-plan.json')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('-f', planFile, 'apply', '-p', planFile)

        then:
        exitCode == 0
        conditions.eventually {
            def config = TestUtils.getDynamicTopicConfig('retention-test-topic')
            assert config['retention.ms'] == '86400000'
            assert config['retention.bytes'] == '1073741824'
        }

        cleanup:
        TestUtils.cleanUpCluster()
    }

    void 'test apply updates retention.ms and retention.bytes to new values'() {
        setup:
        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('retention-test-topic', 3, adminClient,
                    ['retention.ms': '86400000', 'retention.bytes': '1073741824'])
        }
        // Kafka 4.0 has slower topic/config propagation than 3.9;
        // poll for visibility before apply and after apply for config state.
        def conditions = new PollingConditions(timeout: 30, initialDelay: 1, factor: 1.25)
        conditions.eventually {
            assert TestUtils.getTopics().contains('retention-test-topic')
        }
        String planFile = TestUtils.getResourceFilePath('plans/retention-apply-update-plan.json')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('-f', planFile, 'apply', '-p', planFile)

        then:
        exitCode == 0
        conditions.eventually {
            def config = TestUtils.getDynamicTopicConfig('retention-test-topic')
            assert config['retention.ms'] == '-1'
            assert config['retention.bytes'] == '524288000'
        }

        cleanup:
        TestUtils.cleanUpCluster()
    }

    void 'test apply removes retention.ms and retention.bytes (reset to broker defaults)'() {
        setup:
        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('retention-test-topic', 3, adminClient,
                    ['retention.ms': '-1', 'retention.bytes': '524288000'])
        }
        // Kafka 4.0 has slower topic/config propagation than 3.9;
        // poll for visibility before apply and after apply for config state.
        def conditions = new PollingConditions(timeout: 30, initialDelay: 1, factor: 1.25)
        conditions.eventually {
            assert TestUtils.getTopics().contains('retention-test-topic')
        }
        String planFile = TestUtils.getResourceFilePath('plans/retention-apply-remove-plan.json')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('-f', planFile, 'apply', '-p', planFile)

        then:
        exitCode == 0
        conditions.eventually {
            def config = TestUtils.getDynamicTopicConfig('retention-test-topic')
            assert !config.containsKey('retention.ms')
            assert !config.containsKey('retention.bytes')
        }

        cleanup:
        TestUtils.cleanUpCluster()
    }

    void 'test stale add plan applies successfully when topic already exists'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        String stateFile = TestUtils.getResourceFilePath('plans/simple.yaml')
        String planFile = TestUtils.getResourceFilePath('plans/simple-plan.json')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('test-topic', 1, adminClient)
        }

        when:
        int exitCode = cmd.execute('-f', stateFile, 'apply', '-p', planFile)

        then:
        exitCode == 0

        then:
        def conditions = new PollingConditions(timeout: 15, initialDelay: 1, factor: 1.25)
        conditions.eventually {
            def topicDescriptions = TestUtils.withAdminClient { adminClient ->
                TestUtils.waitFor(adminClient.describeTopics(['test-topic'] as Set).allTopicNames())
            }
            assert topicDescriptions['test-topic'].partitions().size() == 6
        }

        cleanup:
        System.setOut(oldOut)
        TestUtils.cleanUpCluster()
    }

}
