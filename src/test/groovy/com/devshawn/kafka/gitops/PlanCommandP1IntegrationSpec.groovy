package com.devshawn.kafka.gitops

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.common.acl.AccessControlEntry
import org.apache.kafka.common.acl.AclBinding
import org.apache.kafka.common.acl.AclOperation
import org.apache.kafka.common.acl.AclPermissionType
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourceType
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
                'invalid-default-partitions-1',
                'invalid-topic-partitions',
                'invalid-topic-replication'
        ]
    }

    void 'plans only the changed custom user ACL when one permission flips (issue #24)'() {
        setup:
        TestUtils.cleanUpCluster()
        seedCustomUserAcls()
        File stateFile = File.createTempFile('custom-user-acls-change-', '.yaml')
        File planOutputFile = File.createTempFile('custom-user-acls-change-', '.json')
        stateFile.text = '''
users:
  test-user:
    principal: User:test

customUserAcls:
  test-user:
    describe-configs-topic:
      name: topic1
      type: TOPIC
      pattern: LITERAL
      host: "*"
      operation: DESCRIBE_CONFIGS
      permission: DENY
    describe-topic:
      name: topic1
      type: TOPIC
      pattern: LITERAL
      host: "*"
      operation: DESCRIBE
      permission: ALLOW
    write-topic:
      name: topic1
      type: TOPIC
      pattern: LITERAL
      host: "*"
      operation: WRITE
      permission: ALLOW
    read-topic:
      name: topic1
      type: TOPIC
      pattern: LITERAL
      host: "*"
      operation: READ
      permission: ALLOW
'''

        when:
        int exitCode = new CommandLine(new MainCommand()).execute('-f', stateFile.absolutePath, 'plan', '-o', planOutputFile.absolutePath)
        def plan = new ObjectMapper().readTree(planOutputFile)

        then:
        exitCode == 0
        plan.path('topicPlans').isArray()
        plan.path('topicPlans').size() == 0
        plan.path('aclPlans').size() == 2
        plan.path('aclPlans').findAll { it.path('action').asText() == 'ADD' }.size() == 1
        plan.path('aclPlans').findAll { it.path('action').asText() == 'REMOVE' }.size() == 1

        and:
        def addAcl = plan.path('aclPlans').find { it.path('action').asText() == 'ADD' }
        def removeAcl = plan.path('aclPlans').find { it.path('action').asText() == 'REMOVE' }

        addAcl.path('aclDetails').path('name').asText() == 'topic1'
        addAcl.path('aclDetails').path('principal').asText() == 'User:test'
        addAcl.path('aclDetails').path('operation').asText() == 'DESCRIBE_CONFIGS'
        addAcl.path('aclDetails').path('permission').asText() == 'DENY'

        removeAcl.path('aclDetails').path('name').asText() == 'topic1'
        removeAcl.path('aclDetails').path('principal').asText() == 'User:test'
        removeAcl.path('aclDetails').path('operation').asText() == 'DESCRIBE_CONFIGS'
        removeAcl.path('aclDetails').path('permission').asText() == 'ALLOW'

        cleanup:
        stateFile?.delete()
        planOutputFile?.delete()
        TestUtils.cleanUpCluster()
    }

    private static void seedCustomUserAcls() {
        TestUtils.withAdminClient { adminClient ->
            [
                    aclBinding('topic1', 'User:test', AclOperation.DESCRIBE_CONFIGS, AclPermissionType.ALLOW),
                    aclBinding('topic1', 'User:test', AclOperation.DESCRIBE, AclPermissionType.ALLOW),
                    aclBinding('topic1', 'User:test', AclOperation.WRITE, AclPermissionType.ALLOW),
                    aclBinding('topic1', 'User:test', AclOperation.READ, AclPermissionType.ALLOW),
            ].each { aclBinding ->
                TestUtils.waitFor(adminClient.createAcls([aclBinding]).all())
            }
        }
    }

    private static AclBinding aclBinding(String name, String principal, AclOperation operation, AclPermissionType permission) {
        return new AclBinding(
                new ResourcePattern(ResourceType.TOPIC, name, PatternType.LITERAL),
                new AccessControlEntry(principal, '*', operation, permission)
        )
    }
}
