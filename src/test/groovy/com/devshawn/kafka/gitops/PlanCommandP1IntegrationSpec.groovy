package com.devshawn.kafka.gitops

import org.skyscreamer.jsonassert.JSONAssert
import picocli.CommandLine
import spock.lang.Specification
import spock.lang.Unroll

@Unroll
class PlanCommandP1IntegrationSpec extends Specification {

    void 'deduplicates overlapping ACLs before writing the plan'() {
        setup:
        TestUtils.cleanUpCluster()
        File planOutputFile = File.createTempFile('duplicate-service-acls-', '.json')
        String file = TestUtils.getResourceFilePath('plans/duplicate-service-acls.yaml')
        String expectedPlan = TestUtils.getResourceFileContent('plans/duplicate-service-acls-plan.json')

        when:
        int exitCode = new CommandLine(new MainCommand()).execute('-f', file, 'plan', '-o', planOutputFile.absolutePath)

        then:
        exitCode == 0
        JSONAssert.assertEquals(expectedPlan, TestUtils.getFileContent(planOutputFile.absolutePath), true)

        cleanup:
        planOutputFile?.delete()
        TestUtils.cleanUpCluster()
    }

    void 'rejects invalid topic numeric values during plan generation - #planName'() {
        setup:
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        PrintStream oldOut = System.out
        System.setOut(new PrintStream(out))
        String file = TestUtils.getResourceFilePath("plans/${planName}.yaml")

        when:
        int exitCode = new CommandLine(new MainCommand()).execute('-f', file, 'plan')

        then:
        exitCode == 2
        out.toString() == TestUtils.getResourceFileContent("plans/${planName}-output.txt")

        cleanup:
        System.setOut(oldOut)

        where:
        planName << [
                'invalid-topic-partitions',
                'invalid-topic-replication'
        ]
    }
}
