package com.devshawn.kafka.gitops.manager;

import com.devshawn.kafka.gitops.config.ManagerConfig;
import com.devshawn.kafka.gitops.domain.plan.DesiredPlan;
import com.devshawn.kafka.gitops.domain.plan.TopicConfigPlan;
import com.devshawn.kafka.gitops.domain.plan.TopicDetailsPlan;
import com.devshawn.kafka.gitops.domain.plan.TopicPlan;
import com.devshawn.kafka.gitops.enums.PlanAction;
import com.devshawn.kafka.gitops.exception.TopicAlreadyExistsException;
import com.devshawn.kafka.gitops.exception.ValidationException;
import com.devshawn.kafka.gitops.service.KafkaService;
import com.devshawn.kafka.gitops.util.LogUtil;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.ConfigResource;

import java.util.*;

public class ApplyManager {

    private final ManagerConfig managerConfig;
    private final KafkaService kafkaService;

    public ApplyManager(ManagerConfig managerConfig, KafkaService kafkaService) {
        this.managerConfig = managerConfig;
        this.kafkaService = kafkaService;
    }

    public void applyTopics(DesiredPlan desiredPlan) {
        Collection<Node> clusterNodes = kafkaService.describeClusterNodes();
        desiredPlan.getTopicPlans().forEach(topicPlan -> {
            if (topicPlan.getAction() == PlanAction.ADD) {
                LogUtil.printTopicPreApply(topicPlan);
                try {
                    kafkaService.createTopic(topicPlan.getName(), topicPlan.getTopicDetailsPlan().get(), topicPlan.getTopicConfigPlans());
                } catch (TopicAlreadyExistsException ex) {
                    applyStaleAddTopic(topicPlan, clusterNodes);
                }
                LogUtil.printPostApply();
            } else if (topicPlan.getAction() == PlanAction.UPDATE) {
                LogUtil.printTopicPreApply(topicPlan);
                
                if(topicPlan.getTopicDetailsPlan().isPresent()) {
                    // Update Replication factor and partition number
                    TopicDetailsPlan topicDetailsPlan = topicPlan.getTopicDetailsPlan().get();
                    if(topicDetailsPlan.getPartitionsAction() == PlanAction.UPDATE) {
                        kafkaService.addTopicPartition(topicPlan.getName(), topicDetailsPlan.getPartitions().get());
                    }
                    if(topicDetailsPlan.getReplicationAction() == PlanAction.UPDATE) {
                        kafkaService.updateTopicReplication(clusterNodes, topicPlan.getName(), topicDetailsPlan.getReplication().get());
                    }
                }
                topicPlan.getTopicConfigPlans().forEach(topicConfigPlan -> applyTopicConfiguration(topicPlan, topicConfigPlan));
                LogUtil.printPostApply();
            } else if (topicPlan.getAction() == PlanAction.REMOVE && !managerConfig.isDeleteDisabled()) {
                LogUtil.printTopicPreApply(topicPlan);
                kafkaService.deleteTopic(topicPlan.getName());
                LogUtil.printPostApply();
            }
        });
    }

    private void applyStaleAddTopic(TopicPlan topicPlan, Collection<Node> clusterNodes) {
        TopicDescription currentTopic = kafkaService.getTopicDescription(Collections.singleton(topicPlan.getName())).get(topicPlan.getName());

        topicPlan.getTopicDetailsPlan().ifPresent(topicDetailsPlan -> {
            topicDetailsPlan.getPartitions().ifPresent(desiredPartitions -> {
                int currentPartitions = currentTopic.partitions().size();
                if (desiredPartitions > currentPartitions) {
                    kafkaService.addTopicPartition(topicPlan.getName(), desiredPartitions);
                } else if (desiredPartitions < currentPartitions) {
                    throw new ValidationException(String.format(
                            "Error thrown when attempting to apply a stale Kafka topic add plan: topic %s already exists with %s partitions, which is greater than the desired %s. Re-run plan.",
                            topicPlan.getName(),
                            currentPartitions,
                            desiredPartitions));
                }
            });

            topicDetailsPlan.getReplication().ifPresent(desiredReplication -> {
                int currentReplication = currentTopic.partitions().stream()
                        .findFirst()
                        .map(topicPartitionInfo -> topicPartitionInfo.replicas().size())
                        .orElseThrow(() -> new ValidationException(String.format(
                                "Error thrown when attempting to apply a stale Kafka topic add plan: topic %s has no partitions to inspect. Re-run plan.",
                                topicPlan.getName())));
                if (desiredReplication != currentReplication) {
                    kafkaService.updateTopicReplication(clusterNodes, topicPlan.getName(), desiredReplication);
                }
            });
        });

        topicPlan.getTopicConfigPlans().forEach(topicConfigPlan -> applyTopicConfiguration(topicPlan, topicConfigPlan));
    }

    private void applyTopicConfiguration(TopicPlan topicPlan, TopicConfigPlan topicConfigPlan) {
        Map<ConfigResource, Collection<AlterConfigOp>> configs = new HashMap<>();
        ConfigResource configResource = new ConfigResource(ConfigResource.Type.TOPIC, topicPlan.getName());
        List<AlterConfigOp> configOps = new ArrayList<>();

        ConfigEntry configEntry = new ConfigEntry(topicConfigPlan.getKey(), topicConfigPlan.getValue().orElse(null));

        // TODO: Make OpType work with append/subtract
        if (topicConfigPlan.getAction() == PlanAction.ADD) {
            configOps.add(new AlterConfigOp(configEntry, AlterConfigOp.OpType.SET));
        } else if (topicConfigPlan.getAction() == PlanAction.UPDATE) {
            configOps.add(new AlterConfigOp(configEntry, AlterConfigOp.OpType.SET));
        } else if (topicConfigPlan.getAction() == PlanAction.REMOVE) {
            configOps.add(new AlterConfigOp(configEntry, AlterConfigOp.OpType.DELETE));
        }

        configs.put(configResource, configOps);

        kafkaService.updateTopicConfig(configs);
    }

    public void applyAcls(DesiredPlan desiredPlan) {
        desiredPlan.getAclPlans().stream()
                .filter(aclPlan -> aclPlan.getAction() == PlanAction.ADD)
                .forEach(aclPlan -> {
                    LogUtil.printAclPreApply(aclPlan);
                    kafkaService.createAcl(aclPlan.getAclDetails().toAclBinding());
                    LogUtil.printPostApply();
                });

        desiredPlan.getAclPlans().stream()
                .filter(aclPlan -> aclPlan.getAction() == PlanAction.REMOVE && !managerConfig.isDeleteDisabled())
                .forEach(aclPlan -> {
                    LogUtil.printAclPreApply(aclPlan);
                    kafkaService.deleteAcl(aclPlan.getAclDetails().toAclBinding());
                    LogUtil.printPostApply();
                });
    }
}
