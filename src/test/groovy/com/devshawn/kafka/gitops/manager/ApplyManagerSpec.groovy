package com.devshawn.kafka.gitops.manager

import com.devshawn.kafka.gitops.config.ManagerConfig
import com.devshawn.kafka.gitops.config.KafkaGitopsConfig
import com.devshawn.kafka.gitops.domain.plan.AclPlan
import com.devshawn.kafka.gitops.domain.plan.DesiredPlan
import com.devshawn.kafka.gitops.domain.state.AclDetails
import com.devshawn.kafka.gitops.enums.PlanAction
import com.devshawn.kafka.gitops.service.KafkaService
import spock.lang.Specification

class ApplyManagerSpec extends Specification {

    void 'applyAcls creates before removing stale ACLs (devshawn/kafka-gitops#89)'() {
        given:
        List<String> calls = []
        KafkaService kafkaService = new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build()) {
            @Override
            void createAcl(org.apache.kafka.common.acl.AclBinding aclBinding) {
                calls.add("create:${aclBinding.pattern().name()}")
            }

            @Override
            void deleteAcl(org.apache.kafka.common.acl.AclBinding aclBinding) {
                calls.add("delete:${aclBinding.pattern().name()}")
            }
        }
        ApplyManager sut = new ApplyManager(managerConfig(), kafkaService)
        AclDetails addAcl = aclDetails('new-topic')
        AclDetails removeAcl = aclDetails('old-topic')
        DesiredPlan desiredPlan = new DesiredPlan.Builder()
                .addAclPlans(new AclPlan.Builder().setName('remove-acl').setAclDetails(removeAcl).setAction(PlanAction.REMOVE).build())
                .addAclPlans(new AclPlan.Builder().setName('add-acl').setAclDetails(addAcl).setAction(PlanAction.ADD).build())
                .build()

        when:
        sut.applyAcls(desiredPlan)

        then:
        calls == ['create:new-topic', 'delete:old-topic']
    }

    private static ManagerConfig managerConfig() {
        return new ManagerConfig.Builder()
                .setVerboseRequested(false)
                .setDeleteDisabled(false)
                .setIncludeUnchangedEnabled(false)
                .setSkipAclsDisabled(false)
                .setStateFile(new File('state.yaml'))
                .build()
    }

    private static AclDetails aclDetails(String topicName) {
        return new AclDetails.Builder()
                .setName(topicName)
                .setType('TOPIC')
                .setPattern('LITERAL')
                .setPrincipal('User:test')
                .setHost('*')
                .setOperation('READ')
                .setPermission('ALLOW')
                .build()
    }
}
