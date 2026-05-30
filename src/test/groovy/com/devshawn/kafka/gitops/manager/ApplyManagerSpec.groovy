package com.devshawn.kafka.gitops.manager

import com.devshawn.kafka.gitops.config.ManagerConfig
import com.devshawn.kafka.gitops.config.KafkaGitopsConfig
import com.devshawn.kafka.gitops.domain.plan.AclPlan
import com.devshawn.kafka.gitops.domain.plan.DesiredPlan
import com.devshawn.kafka.gitops.domain.plan.TopicConfigPlan
import com.devshawn.kafka.gitops.domain.plan.TopicDetailsPlan
import com.devshawn.kafka.gitops.domain.plan.TopicPlan
import com.devshawn.kafka.gitops.domain.state.AclDetails
import com.devshawn.kafka.gitops.enums.PlanAction
import com.devshawn.kafka.gitops.exception.KafkaExecutionException
import com.devshawn.kafka.gitops.exception.TopicAlreadyExistsException
import com.devshawn.kafka.gitops.service.KafkaService
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.Node
import org.apache.kafka.common.TopicPartitionInfo
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class ApplyManagerSpec extends Specification {

    void 'applyTopics retries stale add topic description until the existing topic is visible'() {
        given:
        Node broker1 = new Node(1, 'broker1', 9092)
        Node broker2 = new Node(2, 'broker2', 9093)
        AtomicInteger describeAttempts = new AtomicInteger()
        List<String> calls = []
        KafkaService kafkaService = new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build()) {
            @Override
            Collection<Node> describeClusterNodes() {
                return [broker1, broker2]
            }

            @Override
            void createTopic(String topicName, TopicDetailsPlan topicDetailsPlan, List<TopicConfigPlan> topicConfigPlans) {
                throw new TopicAlreadyExistsException(topicName, "Topic '" + topicName + "' already exists with different topic metadata.")
            }

            @Override
            Map<String, TopicDescription> getTopicDescription(Set<String> topics) {
                if (describeAttempts.incrementAndGet() == 1) {
                    throw new KafkaExecutionException(
                            'Error thrown when attempting to describe Kafka topics',
                            'org.apache.kafka.common.errors.UnknownTopicOrPartitionException: This server does not host this topic-partition.')
                }

                return ['test-topic': new TopicDescription('test-topic', false, [
                        new TopicPartitionInfo(0, broker1, [broker1, broker2], [broker1, broker2])
                ])]
            }

            @Override
            void addTopicPartition(String topicName, int partitions) {
                calls.add('partitions:' + topicName + ':' + partitions)
            }
        }
        ApplyManager sut = new ApplyManager(managerConfig(), kafkaService)
        DesiredPlan desiredPlan = new DesiredPlan.Builder()
                .addTopicPlans(topicPlan())
                .build()

        when:
        sut.applyTopics(desiredPlan)

        then:
        describeAttempts.get() == 2
        calls == ['partitions:test-topic:6']
    }

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

    private static TopicPlan topicPlan() {
        TopicDetailsPlan topicDetailsPlan = new TopicDetailsPlan.Builder()
                .setPartitions(6)
                .setPartitionsAction(PlanAction.ADD)
                .setReplication(2)
                .setReplicationAction(PlanAction.ADD)
                .build()

        return new TopicPlan.Builder()
                .setName('test-topic')
                .setAction(PlanAction.ADD)
                .setTopicDetailsPlan(topicDetailsPlan)
                .build()
    }
}
