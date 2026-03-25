package com.devshawn.kafka.gitops.manager

import com.devshawn.kafka.gitops.config.ManagerConfig
import com.devshawn.kafka.gitops.config.KafkaGitopsConfig
import com.devshawn.kafka.gitops.domain.plan.DesiredPlan
import com.devshawn.kafka.gitops.domain.state.DesiredState
import com.devshawn.kafka.gitops.domain.state.TopicDetails
import com.devshawn.kafka.gitops.enums.PlanAction
import com.devshawn.kafka.gitops.service.KafkaService
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.Config
import org.apache.kafka.clients.admin.ConfigEntry
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.Node
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.TopicPartitionInfo
import spock.lang.Specification

class PlanManagerSpec extends Specification {

    void 'planTopics ignores desired configs that already match broker defaults (devshawn/kafka-gitops#103)'() {
        given:
        ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, 'shared-topic')
        KafkaService kafkaService = new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build()) {
            @Override
            Map<String, TopicDescription> getTopics() {
                return ['shared-topic': topicDescription('shared-topic', 3, 2)]
            }

            @Override
            Map<ConfigResource, Config> describeConfigsForTopics(List<String> topicNames) {
                return [
                        (configResource): new Config([
                                new ConfigEntry(
                                        'compression.type',
                                        'producer',
                                        ConfigEntry.ConfigSource.DEFAULT_CONFIG,
                                        false,
                                        false,
                                        [],
                                        ConfigEntry.ConfigType.STRING,
                                        ''
                                )
                        ])
                ]
            }
        }
        PlanManager sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        DesiredState desiredState = new DesiredState.Builder()
                .putTopics('shared-topic', new TopicDetails.Builder()
                        .setPartitions(3)
                        .setReplication(2)
                        .putConfigs('compression.type', 'producer')
                        .build())
                .build()
        DesiredPlan.Builder desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        desiredPlan.build().getTopicPlans().size() == 1
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.NO_CHANGE
        topicPlan.getTopicConfigPlans().size() == 1
        topicPlan.getTopicConfigPlans().first().getAction() == PlanAction.NO_CHANGE
        topicPlan.getTopicConfigPlans().first().getKey() == 'compression.type'
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

    private static TopicDescription topicDescription(String name, int partitions, int replication) {
        List<Node> replicas = (1..replication).collect { id -> new Node(id, "broker-${id}", 9092 + id) }
        List<TopicPartitionInfo> partitionInfos = (0..<partitions).collect { partition ->
            new TopicPartitionInfo(partition, replicas.first(), replicas, replicas)
        }
        return new TopicDescription(name, false, partitionInfos)
    }
}
