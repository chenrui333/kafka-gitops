package com.devshawn.kafka.gitops

import picocli.CommandLine
import spock.lang.Specification
import spock.lang.Unroll

@Unroll
class ValidateCommandP1IntegrationSpec extends Specification {

    void 'rejects custom ACL references to undefined owners - #planName'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        String file = TestUtils.getResourceFilePath("plans/${planName}.yaml")

        when:
        int exitCode = new CommandLine(new MainCommand()).execute('-f', file, 'validate')

        then:
        exitCode == 2
        out.toString() == TestUtils.getResourceFileContent("plans/${planName}-validate-output.txt")

        cleanup:
        System.setOut(oldOut)

        where:
        planName << [
                'invalid-custom-service-acls-3',
                'invalid-custom-user-acls-3'
        ]
    }
}
