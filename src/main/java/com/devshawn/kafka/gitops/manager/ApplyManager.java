package com.devshawn.kafka.gitops.manager;

import com.devshawn.kafka.gitops.config.ManagerConfig;
import com.devshawn.kafka.gitops.domain.plan.DesiredPlan;
import com.devshawn.kafka.gitops.domain.plan.TopicConfigPlan;
import com.devshawn.kafka.gitops.domain.plan.TopicDetailsPlan;
import com.devshawn.kafka.gitops.domain.plan.TopicPlan;
import com.devshawn.kafka.gitops.enums.PlanAction;
import com.devshawn.kafka.gitops.exception.KafkaExecutionException;
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
    private static final int STALE_ADD_TOPIC_DESCRIBE_ATTEMPTS = 20;
    private static final long STALE_ADD_TOPIC_DESCRIBE_RETRY_MS = 500L;

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
                topicPlan.getTopicConfigPlans().stream()
                        .filter(c -> c.getAction() != PlanAction.NO_CHANGE)
                        .forEach(topicConfigPlan -> applyTopicConfiguration(topicPlan, topicConfigPlan));
                LogUtil.printPostApply();
            } else if (topicPlan.getAction() == PlanAction.REMOVE && !managerConfig.isDeleteDisabled()) {
                LogUtil.printTopicPreApply(topicPlan);
                kafkaService.deleteTopic(topicPlan.getName());
                LogUtil.printPostApply();
            }
        });
    }

    private void applyStaleAddTopic(TopicPlan topicPlan, Collection<Node> clusterNodes) {
        TopicDescription currentTopic = describeStaleAddTopic(topicPlan);

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

        topicPlan.getTopicConfigPlans().stream()
                .filter(c -> c.getAction() != PlanAction.NO_CHANGE)
                .forEach(topicConfigPlan -> applyTopicConfiguration(topicPlan, topicConfigPlan));
    }

    private TopicDescription describeStaleAddTopic(TopicPlan topicPlan) {
        Set<String> topicNames = Collections.singleton(topicPlan.getName());
        KafkaExecutionException lastException = null;

        for (int attempt = 1; attempt <= STALE_ADD_TOPIC_DESCRIBE_ATTEMPTS; attempt++) {
            try {
                TopicDescription topicDescription = kafkaService.getTopicDescription(topicNames).get(topicPlan.getName());
                if (topicDescription != null) {
                    return topicDescription;
                }
            } catch (KafkaExecutionException ex) {
                if (!isTopicDescriptionNotReady(ex)) {
                    throw ex;
                }
                lastException = ex;
            }

            if (attempt < STALE_ADD_TOPIC_DESCRIBE_ATTEMPTS) {
                sleepBeforeRetryingStaleAddDescription();
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        throw new ValidationException(String.format(
                "Error thrown when attempting to apply a stale Kafka topic add plan: topic %s was not returned by Kafka topic description. Re-run plan.",
                topicPlan.getName()));
    }

    private static boolean isTopicDescriptionNotReady(KafkaExecutionException ex) {
        String message = String.format("%s %s", ex.getMessage(), ex.getExceptionMessage()).toLowerCase(Locale.ROOT);
        return message.contains("unknowntopicorpartition")
                || message.contains("unknown topic or partition")
                || message.contains("does not host this topic-partition");
    }

    private static void sleepBeforeRetryingStaleAddDescription() {
        try {
            Thread.sleep(STALE_ADD_TOPIC_DESCRIBE_RETRY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KafkaExecutionException("Error thrown when attempting to describe Kafka topics", ex.getMessage());
        }
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
