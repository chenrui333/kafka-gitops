package com.devshawn.kafka.gitops

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.PartitionReassignment
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.acl.*
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourcePatternFilter
import org.apache.kafka.common.resource.ResourceType
import spock.util.concurrent.PollingConditions

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class TestUtils {
    private static final int CLEANUP_ATTEMPTS = 30
    private static final long CLEANUP_RETRY_MS = 2000L

    static String getFileContent(String fileName) {
        File file = new File(fileName)
        return file.text
    }

    static String getResourceFileContent(String fileName) {
        URL res = TestUtils.getClassLoader().getResource(fileName)
        File file = Paths.get(res.toURI()).toFile()
        return file.text
    }

    static String getResourceFilePath(String fileName) {
        URL res = TestUtils.getClassLoader().getResource(fileName)
        File file = Paths.get(res.toURI()).toFile()
        return file.getAbsolutePath()
    }

    static void cleanUpCluster() {
        try {
            List<AclBindingFilter> filters = getCleanupFilters()
            withAdminClient { adminClient ->
                Set<String> deletedTopics = [] as Set<String>
                KafkaFuture<Void> topicDeletionFuture = null
                int stableTopicDeletionChecks = 0

                waitForCleanup('partition reassignments to finish') {
                    Map<TopicPartition, PartitionReassignment> reassignments = waitFor(adminClient.listPartitionReassignments().reassignments())
                    return reassignments.isEmpty() ? null : "Pending reassignments: ${formatReassignments(reassignments)}"
                }

                waitForCleanup('topics to be deleted') {
                    Set<String> topics = waitFor(adminClient.listTopics().names())
                    if (!topics.isEmpty()) {
                        stableTopicDeletionChecks = 0
                        deletedTopics.addAll(topics)
                        topicDeletionFuture = adminClient.deleteTopics(topics).all()
                    }

                    if (topicDeletionFuture != null) {
                        try {
                            waitFor(topicDeletionFuture)
                        } catch (Exception ex) {
                            return "Delete request still in progress for ${deletedTopics.toList().sort()}: ${ex.message}"
                        }
                    }

                    Set<String> remainingTopics = waitFor(adminClient.listTopics().names())
                    if (!remainingTopics.isEmpty()) {
                        stableTopicDeletionChecks = 0
                        return "Remaining topics: ${remainingTopics.toList().sort()}"
                    }

                    if (!deletedTopics.isEmpty() && stableTopicDeletionChecks < 1) {
                        stableTopicDeletionChecks++
                        return "Waiting for deleted topics to settle: ${deletedTopics.toList().sort()}"
                    }

                    return null
                }

                waitForCleanup('ACLs to be deleted') {
                    Map<String, Integer> remainingAcls = [:]
                    filters.each { filter ->
                        List<AclBinding> acls = new ArrayList<>(waitFor(adminClient.describeAcls(filter).values()))
                        if (!acls.isEmpty()) {
                            try {
                                waitFor(adminClient.deleteAcls(Collections.singletonList(filter)).all())
                            } catch (Exception ignored) {
                                // ACL deletion is idempotent; re-check the live state before failing.
                            }

                            List<AclBinding> remaining = new ArrayList<>(waitFor(adminClient.describeAcls(filter).values()))
                            if (!remaining.isEmpty()) {
                                remainingAcls[filter.patternFilter().resourceType().name()] = remaining.size()
                            }
                        }
                    }
                    return remainingAcls.isEmpty() ? null : "Remaining ACLs: ${remainingAcls}"
                }
            }
            println "Finished cleaning up cluster"
        } catch (Exception ex) {
            throw new IllegalStateException('Error cleaning up kafka cluster', ex)
        }
    }

    static void seedCluster() {
        def conditions = new PollingConditions(timeout: 60, initialDelay: 2, factor: 1.25)

        try {
            withAdminClient { adminClient ->
                createTopic("delete-topic", 1, adminClient)
                createTopic("test-topic", 1, adminClient)
                createTopic("topic-with-configs-1", 3, adminClient, ["cleanup.policy": "compact", "segment.bytes": "100000"])
                createTopic("topic-with-configs-2", 6, adminClient, ["retention.ms": "60000"])
                createAcl(adminClient)

                conditions.eventually {
                    Set<String> newTopics = adminClient.listTopics().names().get()
                    assert newTopics.size() == 4

                    List<AclBinding> newAcls = new ArrayList<>(waitFor(adminClient.describeAcls(getWildcardFilter()).values()))
                    assert newAcls.size() == 1
                }
            }
            println "Finished seeding kafka cluster"
        } catch (Exception ex) {
            println "Error seeding up kafka cluster"
            ex.printStackTrace()
        }
    }

    static void createTopic(String name, int partitions, AdminClient adminClient) {
        createTopic(name, partitions, adminClient, null)
    }

    static void createTopic(String name, int partitions, AdminClient adminClient, Map<String, String> configs) {
        NewTopic newTopic = new NewTopic(name, partitions, (short) 2)
        if (configs != null) {
            newTopic.configs(configs)
        }
        waitFor(adminClient.createTopics(Collections.singletonList(newTopic)).all())
    }

    static void createAcl(AdminClient adminClient) {
        ResourcePattern resourcePattern = new ResourcePattern(ResourceType.TOPIC, "test-topic", PatternType.LITERAL)
        AccessControlEntry accessControlEntry = new AccessControlEntry("User:test", "*", AclOperation.READ, AclPermissionType.ALLOW)
        AclBinding aclBinding = new AclBinding(resourcePattern, accessControlEntry)
        waitFor(adminClient.createAcls(Collections.singletonList(aclBinding)).all())
    }

    static AclBindingFilter getWildcardFilter() {
        ResourcePatternFilter resourcePatternFilter = new ResourcePatternFilter(ResourceType.ANY, null, PatternType.ANY)
        AccessControlEntryFilter accessFilter = new AccessControlEntryFilter(null, null, AclOperation.ANY, AclPermissionType.ANY)
        return new AclBindingFilter(resourcePatternFilter, accessFilter)
    }

    static List<AclBindingFilter> getCleanupFilters() {
        return [
                getFilter(ResourceType.TOPIC),
                getFilter(ResourceType.GROUP),
                getFilter(ResourceType.CLUSTER),
        ]
    }

    private static AclBindingFilter getFilter(ResourceType resourceType) {
        ResourcePatternFilter resourcePatternFilter = new ResourcePatternFilter(resourceType, null, PatternType.ANY)
        AccessControlEntryFilter accessFilter = new AccessControlEntryFilter(null, null, AclOperation.ANY, AclPermissionType.ANY)
        return new AclBindingFilter(resourcePatternFilter, accessFilter)
    }

    static Set<String> getTopics() {
        return withAdminClient { adminClient ->
            waitFor(adminClient.listTopics().names())
        }
    }

    static List<AclBinding> getAcls(AclBindingFilter filter) {
        return withAdminClient { adminClient ->
            new ArrayList<>(waitFor(adminClient.describeAcls(filter).values()))
        }
    }

    static Map<TopicPartition, PartitionReassignment> getPartitionReassignments() {
        return withAdminClient { adminClient ->
            waitFor(adminClient.listPartitionReassignments().reassignments())
        }
    }

    private static void waitForCleanup(String description, Closure<String> stateProbe) {
        String lastState = "Timed out waiting for ${description}"
        Exception lastException = null

        for (int attempt = 1; attempt <= CLEANUP_ATTEMPTS; attempt++) {
            try {
                String state = stateProbe.call()
                if (state == null) {
                    return
                }
                lastState = state
                lastException = null
            } catch (Exception ex) {
                lastException = ex
                lastState = ex.message ?: ex.class.simpleName
            }

            if (attempt < CLEANUP_ATTEMPTS) {
                Thread.sleep(CLEANUP_RETRY_MS)
            }
        }

        throw new IllegalStateException("Timed out waiting for ${description}. ${lastState}", lastException)
    }

    private static List<String> formatReassignments(Map<TopicPartition, PartitionReassignment> reassignments) {
        return reassignments.keySet()
                .collect { topicPartition -> "${topicPartition.topic()}-${topicPartition.partition()}" }
                .sort()
    }

    static void deleteTopics() {
        withAdminClient { adminClient ->
            Set<String> topics = waitFor(adminClient.listTopics().names())
            if (!topics.isEmpty()) {
                waitFor(adminClient.deleteTopics(topics).all())
            }
        }
    }

    static void deleteAcls(AclBindingFilter filter) {
        withAdminClient { adminClient ->
            List<AclBinding> acls = new ArrayList<>(waitFor(adminClient.describeAcls(filter).values()))
            if (!acls.isEmpty()) {
                waitFor(adminClient.deleteAcls(Collections.singletonList(filter)).all())
            }
        }
    }

    static <T> T waitFor(KafkaFuture<T> future) {
        return future.get(10, TimeUnit.SECONDS)
    }

    static <T> T withAdminClient(Closure<T> action) {
        AdminClient adminClient = AdminClient.create(getKafkaConfig())
        try {
            return action.call(adminClient)
        } finally {
            adminClient.close()
        }
    }

    static Map<String, Object> getKafkaConfig() {
        String jaasConfig = String.format("org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";",
                System.getenv("KAFKA_SASL_JAAS_USERNAME"), System.getenv("KAFKA_SASL_JAAS_PASSWORD"))
        return [
                (CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG): System.getenv("KAFKA_BOOTSTRAP_SERVERS"),
                (CommonClientConfigs.SECURITY_PROTOCOL_CONFIG): System.getenv("KAFKA_SECURITY_PROTOCOL"),
                (SaslConfigs.SASL_MECHANISM)                  : System.getenv("KAFKA_SASL_MECHANISM"),
                (SaslConfigs.SASL_JAAS_CONFIG)                : jaasConfig,
        ]
    }
}
