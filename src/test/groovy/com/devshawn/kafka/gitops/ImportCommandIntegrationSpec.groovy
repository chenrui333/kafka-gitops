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
}
