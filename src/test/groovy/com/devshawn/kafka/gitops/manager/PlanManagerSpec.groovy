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
import spock.lang.Unroll

@Unroll
class PlanManagerSpec extends Specification {

    // --- Retention-specific unit tests (chenrui333/kafka-gitops#34) ---

    void 'retention.ms ADD when topic has no existing retention config'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, [:])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, ['retention.ms': '86400000'])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def plan = desiredPlan.build()
        plan.getTopicPlans().size() == 1
        def topicPlan = plan.getTopicPlans().first()
        topicPlan.getAction() == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().size() == 1
        def configPlan = topicPlan.getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.ms'
        configPlan.getValue().get() == '86400000'
        configPlan.getAction() == PlanAction.ADD
    }

    void 'retention.ms UPDATE when value changes from #previousValue to #newValue'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['retention.ms': previousValue])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, ['retention.ms': newValue])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def configPlan = desiredPlan.build().getTopicPlans().first().getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.ms'
        configPlan.getValue().get() == newValue
        configPlan.getPreviousValue().get() == previousValue
        configPlan.getAction() == PlanAction.UPDATE

        where:
        previousValue  | newValue
        '604800000'    | '86400000'
        '60000'        | '-1'
        '-1'           | '604800000'
        '604800000'    | '0'
    }

    void 'retention.ms NO_CHANGE when value is already #value'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['retention.ms': value])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, ['retention.ms': value])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.NO_CHANGE
        topicPlan.getTopicConfigPlans().first().getAction() == PlanAction.NO_CHANGE

        where:
        value << ['-1', '0', '60000', '604800000', '999999999999']
    }

    void 'retention.ms REMOVE when config is dropped from desired state'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['retention.ms': '60000'])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, [:])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().size() == 1
        def configPlan = topicPlan.getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.ms'
        configPlan.getPreviousValue().get() == '60000'
        configPlan.getAction() == PlanAction.REMOVE
    }

    void 'retention.bytes ADD when topic has no existing retention.bytes'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, [:])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, ['retention.bytes': '1073741824'])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def configPlan = desiredPlan.build().getTopicPlans().first().getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.bytes'
        configPlan.getValue().get() == '1073741824'
        configPlan.getAction() == PlanAction.ADD
    }

    void 'retention.bytes UPDATE from #previousValue to #newValue'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['retention.bytes': previousValue])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, ['retention.bytes': newValue])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def configPlan = desiredPlan.build().getTopicPlans().first().getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.bytes'
        configPlan.getValue().get() == newValue
        configPlan.getPreviousValue().get() == previousValue
        configPlan.getAction() == PlanAction.UPDATE

        where:
        previousValue    | newValue
        '1073741824'     | '524288000'
        '-1'             | '1073741824'
        '1073741824'     | '-1'
    }

    void 'retention.bytes REMOVE when config is dropped from desired state'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['retention.bytes': '1073741824'])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, [:])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.UPDATE
        def configPlan = topicPlan.getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.bytes'
        configPlan.getAction() == PlanAction.REMOVE
    }

    void 'retention.ms and retention.bytes can be set together'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, [:])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, [
                'retention.ms': '86400000',
                'retention.bytes': '1073741824'
        ])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().size() == 2
        topicPlan.getTopicConfigPlans().find { it.key == 'retention.ms' }.action == PlanAction.ADD
        topicPlan.getTopicConfigPlans().find { it.key == 'retention.bytes' }.action == PlanAction.ADD
    }

    void 'retention configs interact correctly with cleanup.policy=compact'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['cleanup.policy': 'delete'])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, [
                'cleanup.policy': 'compact',
                'retention.ms': '-1',
                'retention.bytes': '-1'
        ])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().size() == 3
        topicPlan.getTopicConfigPlans().find { it.key == 'cleanup.policy' }.action == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().find { it.key == 'retention.ms' }.action == PlanAction.ADD
        topicPlan.getTopicConfigPlans().find { it.key == 'retention.bytes' }.action == PlanAction.ADD
    }

    void 'retention with min.insync.replicas interaction'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, ['min.insync.replicas': '1'])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, [
                'retention.ms': '604800000',
                'min.insync.replicas': '2'
        ])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def topicPlan = desiredPlan.build().getTopicPlans().first()
        topicPlan.getAction() == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().find { it.key == 'retention.ms' }.action == PlanAction.ADD
        topicPlan.getTopicConfigPlans().find { it.key == 'min.insync.replicas' }.action == PlanAction.UPDATE
        topicPlan.getTopicConfigPlans().find { it.key == 'min.insync.replicas' }.previousValue.get() == '1'
    }

    void 'retention.ms edge case: very large value #value'() {
        given:
        def kafkaService = stubKafkaService('my-topic', 3, 2, [:])
        def sut = new PlanManager(managerConfig(), kafkaService, new ObjectMapper())
        def desiredState = desiredStateWith('my-topic', 3, 2, ['retention.ms': value])
        def desiredPlan = new DesiredPlan.Builder()

        when:
        sut.planTopics(desiredState, desiredPlan)

        then:
        def configPlan = desiredPlan.build().getTopicPlans().first().getTopicConfigPlans().first()
        configPlan.getKey() == 'retention.ms'
        configPlan.getValue().get() == value
        configPlan.getAction() == PlanAction.ADD

        where:
        value << ['999999999999', '31536000000', '0']
    }

    // --- Original tests ---

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

    private static KafkaService stubKafkaService(String topicName, int partitions, int replication,
                                                  Map<String, String> dynamicConfigs) {
        ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicName)
        List<ConfigEntry> configEntries = dynamicConfigs.collect { key, value ->
            new ConfigEntry(key, value, ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG,
                    false, false, [], ConfigEntry.ConfigType.STRING, '')
        }

        return new KafkaService(new KafkaGitopsConfig.Builder().putConfig('bootstrap.servers', 'unused').build()) {
            @Override
            Map<String, TopicDescription> getTopics() {
                return [(topicName): topicDescription(topicName, partitions, replication)]
            }

            @Override
            Map<ConfigResource, Config> describeConfigsForTopics(List<String> topicNames) {
                return [(configResource): new Config(configEntries)]
            }
        }
    }

    private static DesiredState desiredStateWith(String topicName, int partitions, int replication,
                                                  Map<String, String> configs) {
        def topicBuilder = new TopicDetails.Builder()
                .setPartitions(partitions)
                .setReplication(replication)
        configs.each { key, value -> topicBuilder.putConfigs(key, value) }
        return new DesiredState.Builder()
                .putTopics(topicName, topicBuilder.build())
                .build()
    }
}
