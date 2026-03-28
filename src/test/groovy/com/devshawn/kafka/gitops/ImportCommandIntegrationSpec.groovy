package com.devshawn.kafka.gitops

import com.devshawn.kafka.gitops.domain.state.DesiredStateFile
import com.devshawn.kafka.gitops.service.ParserService
import picocli.CommandLine
import spock.lang.Specification

class ImportCommandIntegrationSpec extends Specification {

    void setup() {
        TestUtils.cleanUpCluster()
    }

    void 'test import writes a valid desired state file from the current cluster'() {
        given:
        TestUtils.seedCluster()
        File outputFile = File.createTempFile('kafka-gitops-import', '.yaml')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int importExitCode = cmd.execute('import', '-o', outputFile.absolutePath)

        then:
        importExitCode == 0
        outputFile.exists()

        when:
        DesiredStateFile importedState = new ParserService(outputFile).parseStateFile()

        then:
        importedState.getTopics().keySet() == ['delete-topic', 'test-topic', 'topic-with-configs-1', 'topic-with-configs-2'] as Set
        importedState.getTopics().get('topic-with-configs-1').getConfigs().get('cleanup.policy') == 'compact'
        importedState.getTopics().get('topic-with-configs-1').getConfigs().get('segment.bytes') == '100000'
        importedState.getTopics().get('topic-with-configs-2').getConfigs().get('retention.ms') == '60000'
        importedState.getUsers().values().any { user -> user.getPrincipal().orElse(null) == 'User:test' }
        importedState.getCustomUserAcls().values().any { aclMap ->
            aclMap.values().any { acl ->
                acl.getName() == 'test-topic' &&
                        acl.getType() == 'TOPIC' &&
                        acl.getPattern() == 'LITERAL' &&
                        acl.getOperation() == 'READ' &&
                        acl.getPermission() == 'ALLOW'
            }
        }

        when:
        int validateExitCode = new CommandLine(new MainCommand()).execute('-f', outputFile.absolutePath, 'validate')

        then:
        validateExitCode == 0

        cleanup:
        outputFile.delete()
        TestUtils.cleanUpCluster()
    }

    void 'test import captures retention.bytes correctly'() {
        given:
        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('retention-bytes-topic', 3, adminClient, ['retention.bytes': '1073741824'])
        }
        File outputFile = File.createTempFile('kafka-gitops-import-retention-bytes', '.yaml')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('import', '-o', outputFile.absolutePath)

        then:
        exitCode == 0

        when:
        DesiredStateFile importedState = new ParserService(outputFile).parseStateFile()

        then:
        importedState.getTopics().get('retention-bytes-topic').getConfigs().get('retention.bytes') == '1073741824'

        cleanup:
        outputFile.delete()
        TestUtils.cleanUpCluster()
    }

    void 'test import captures retention.ms=-1 (infinite) correctly'() {
        given:
        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('infinite-retention-topic', 1, adminClient, ['retention.ms': '-1'])
        }
        File outputFile = File.createTempFile('kafka-gitops-import-retention-infinite', '.yaml')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('import', '-o', outputFile.absolutePath)

        then:
        exitCode == 0

        when:
        DesiredStateFile importedState = new ParserService(outputFile).parseStateFile()

        then:
        importedState.getTopics().get('infinite-retention-topic').getConfigs().get('retention.ms') == '-1'

        cleanup:
        outputFile.delete()
        TestUtils.cleanUpCluster()
    }

    void 'test import captures retention.bytes=-1 (no limit) correctly'() {
        given:
        TestUtils.withAdminClient { adminClient ->
            TestUtils.createTopic('no-limit-bytes-topic', 1, adminClient, ['retention.bytes': '-1'])
        }
        File outputFile = File.createTempFile('kafka-gitops-import-retention-bytes-neg', '.yaml')
        MainCommand mainCommand = new MainCommand()
        CommandLine cmd = new CommandLine(mainCommand)

        when:
        int exitCode = cmd.execute('import', '-o', outputFile.absolutePath)

        then:
        exitCode == 0

        when:
        DesiredStateFile importedState = new ParserService(outputFile).parseStateFile()

        then:
        importedState.getTopics().get('no-limit-bytes-topic').getConfigs().get('retention.bytes') == '-1'

        cleanup:
        outputFile.delete()
        TestUtils.cleanUpCluster()
    }
}
