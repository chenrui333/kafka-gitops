package com.devshawn.kafka.gitops;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.devshawn.kafka.gitops.config.KafkaGitopsConfig;
import com.devshawn.kafka.gitops.config.KafkaGitopsConfigLoader;
import com.devshawn.kafka.gitops.config.ManagerConfig;
import com.devshawn.kafka.gitops.domain.confluent.ServiceAccount;
import com.devshawn.kafka.gitops.domain.options.GetAclOptions;
import com.devshawn.kafka.gitops.domain.plan.DesiredPlan;
import com.devshawn.kafka.gitops.domain.state.AclDetails;
import com.devshawn.kafka.gitops.domain.state.CustomAclDetails;
import com.devshawn.kafka.gitops.domain.state.DesiredState;
import com.devshawn.kafka.gitops.domain.state.DesiredStateFile;
import com.devshawn.kafka.gitops.domain.state.TopicDetails;
import com.devshawn.kafka.gitops.domain.state.service.KafkaStreamsService;
import com.devshawn.kafka.gitops.exception.ConfluentCloudException;
import com.devshawn.kafka.gitops.exception.InvalidAclDefinitionException;
import com.devshawn.kafka.gitops.exception.MissingConfigurationException;
import com.devshawn.kafka.gitops.exception.ServiceAccountNotFoundException;
import com.devshawn.kafka.gitops.exception.ValidationException;
import com.devshawn.kafka.gitops.manager.ApplyManager;
import com.devshawn.kafka.gitops.manager.PlanManager;
import com.devshawn.kafka.gitops.service.ConfluentCloudService;
import com.devshawn.kafka.gitops.service.KafkaService;
import com.devshawn.kafka.gitops.service.ParserService;
import com.devshawn.kafka.gitops.service.RoleService;
import com.devshawn.kafka.gitops.util.LogUtil;
import com.devshawn.kafka.gitops.util.StateUtil;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class StateManager {

    private static org.slf4j.Logger log = LoggerFactory.getLogger(StateManager.class);

    private final ManagerConfig managerConfig;
    private final ObjectMapper objectMapper;
    private final ParserService parserService;
    private final KafkaService kafkaService;
    private final RoleService roleService;
    private final ConfluentCloudService confluentCloudService;

    private PlanManager planManager;
    private ApplyManager applyManager;

    private boolean describeAclEnabled = false;

    public StateManager(ManagerConfig managerConfig, ParserService parserService) {
        initializeLogger(managerConfig.isVerboseRequested());
        this.managerConfig = managerConfig;
        this.objectMapper = initializeObjectMapper();
        KafkaGitopsConfig config = KafkaGitopsConfigLoader.load(managerConfig.getConfigFile().orElse(null));
        this.kafkaService = new KafkaService(config);
        this.parserService = parserService;
        this.roleService = new RoleService();
        this.confluentCloudService = new ConfluentCloudService(objectMapper);
        this.planManager = new PlanManager(managerConfig, kafkaService, objectMapper);
        this.applyManager = new ApplyManager(managerConfig, kafkaService);
    }

    public DesiredStateFile getAndValidateStateFile() {
        DesiredStateFile desiredStateFile = parserService.parseStateFile();
        validateTopics(desiredStateFile);
        validateCustomAcls(desiredStateFile);
        this.describeAclEnabled = StateUtil.isDescribeTopicAclEnabled(desiredStateFile);
        return desiredStateFile;
    }

    public DesiredPlan plan() {
        DesiredPlan desiredPlan = generatePlan();
        planManager.writePlanToFile(desiredPlan);
        planManager.validatePlanHasChanges(desiredPlan, managerConfig.isDeleteDisabled(), managerConfig.isSkipAclsDisabled());
        return desiredPlan;
    }

    private DesiredPlan generatePlan() {
        DesiredState desiredState = getDesiredState();
        DesiredPlan.Builder desiredPlan = new DesiredPlan.Builder();
        if (!managerConfig.isSkipAclsDisabled()) {
            planManager.planAcls(desiredState, desiredPlan);
        }
        planManager.planTopics(desiredState, desiredPlan);
        return desiredPlan.build();
    }

    public DesiredPlan apply() {
        DesiredPlan desiredPlan = planManager.readPlanFromFile();
        if (desiredPlan == null) {
            desiredPlan = generatePlan();
        }

        planManager.validatePlanHasChanges(desiredPlan, managerConfig.isDeleteDisabled(), managerConfig.isSkipAclsDisabled());

        applyManager.applyTopics(desiredPlan);
        if (!managerConfig.isSkipAclsDisabled()) {
            applyManager.applyAcls(desiredPlan);
        }

        return desiredPlan;
    }

    public void createServiceAccounts() {
        DesiredStateFile desiredStateFile = parserService.parseStateFile();
        List<ServiceAccount> serviceAccounts = confluentCloudService.getServiceAccounts();
        AtomicInteger count = new AtomicInteger();
        if (isConfluentCloudEnabled(desiredStateFile)) {
            desiredStateFile.getServices().forEach((name, service) -> {
                createServiceAccount(name, serviceAccounts, count, false);
            });

            desiredStateFile.getUsers().forEach((name, user) -> {
                createServiceAccount(name, serviceAccounts, count, true);
            });
        } else {
            throw new ConfluentCloudException("Confluent Cloud must be enabled in the state file to use this command.");
        }

        if (count.get() == 0) {
            LogUtil.printSimpleSuccess("No service accounts were created as there are no new service accounts.");
        }
    }

    private void createServiceAccount(String name, List<ServiceAccount> serviceAccounts, AtomicInteger count, boolean isUser) {
        String fullName = isUser ? String.format("user-%s", name) : name;
        if (serviceAccounts.stream().noneMatch(it -> it.getName().equals(fullName))) {
            confluentCloudService.createServiceAccount(name, isUser);
            LogUtil.printSimpleSuccess(String.format("Successfully created service account: %s", fullName));
            count.getAndIncrement();
        }
    }

    private DesiredState getDesiredState() {
        DesiredStateFile desiredStateFile = getAndValidateStateFile();
        DesiredState.Builder desiredState = new DesiredState.Builder()
                .addAllPrefixedTopicsToIgnore(getPrefixedTopicsToIgnore(desiredStateFile))
                .addAllPrefixedTopicsToManage(getPrefixedTopicsToManage(desiredStateFile));

        generateTopicsState(desiredState, desiredStateFile);

        if (isConfluentCloudEnabled(desiredStateFile)) {
            generateConfluentCloudServiceAcls(desiredState, desiredStateFile);
            generateConfluentCloudUserAcls(desiredState, desiredStateFile);
        } else {
            generateServiceAcls(desiredState, desiredStateFile);
            generateUserAcls(desiredState, desiredStateFile);
        }

        return desiredState.build();
    }

    private void generateTopicsState(DesiredState.Builder desiredState, DesiredStateFile desiredStateFile) {
        Optional<Integer> defaultPartitions = StateUtil.fetchPartitions(desiredStateFile);
        Optional<Integer> defaultReplication = StateUtil.fetchReplication(desiredStateFile);

        desiredStateFile.getTopics().forEach((name, details) -> {
            Integer partitions = details.getPartitions().orElseGet(defaultPartitions::get);
            Integer replication = details.getReplication().orElseGet(defaultReplication::get);

            desiredState.putTopics(name, new TopicDetails.Builder()
                    .mergeFrom(details)
                    .setPartitions(partitions)
                    .setReplication(replication)
                    .build());
        });
    }

    private void generateConfluentCloudServiceAcls(DesiredState.Builder desiredState, DesiredStateFile desiredStateFile) {
        List<ServiceAccount> serviceAccounts = confluentCloudService.getServiceAccounts();
        desiredStateFile.getServices().forEach((name, service) -> {
            AtomicInteger index = new AtomicInteger(0);

            Optional<ServiceAccount> serviceAccount = serviceAccounts.stream().filter(it -> it.getName().equals(name)).findFirst();
            String serviceAccountId = serviceAccount.orElseThrow(() -> new ServiceAccountNotFoundException(name)).getId();

            service.getAcls(buildGetAclOptions(name)).forEach(aclDetails -> {
                aclDetails.setPrincipal(String.format("User:%s", serviceAccountId));
                putAclIfAbsent(desiredState, name, index, aclDetails.build());
            });

            if (desiredStateFile.getCustomServiceAcls().containsKey(name)) {
                Map<String, CustomAclDetails> customAcls = desiredStateFile.getCustomServiceAcls().get(name);
                customAcls.forEach((aclName, customAcl) -> {
                    AclDetails.Builder aclDetails = AclDetails.fromCustomAclDetails(customAcl);
                    aclDetails.setPrincipal(String.format("User:%s", serviceAccountId));
                    putAclIfAbsent(desiredState, name, index, aclDetails.build());
                });
            }
        });
    }

    private void generateConfluentCloudUserAcls(DesiredState.Builder desiredState, DesiredStateFile desiredStateFile) {
        List<ServiceAccount> serviceAccounts = confluentCloudService.getServiceAccounts();
        desiredStateFile.getUsers().forEach((name, user) -> {
            AtomicInteger index = new AtomicInteger(0);
            String serviceAccountName = String.format("user-%s", name);

            Optional<ServiceAccount> serviceAccount = serviceAccounts.stream().filter(it -> it.getName().equals(serviceAccountName)).findFirst();
            String serviceAccountId = serviceAccount.orElseThrow(() -> new ServiceAccountNotFoundException(serviceAccountName)).getId();

            user.getRoles().forEach(role -> {
                List<AclDetails.Builder> acls = roleService.getAcls(role, String.format("User:%s", serviceAccountId));
                acls.forEach(acl -> putAclIfAbsent(desiredState, name, index, acl.build()));
            });

            if (desiredStateFile.getCustomUserAcls().containsKey(name)) {
                Map<String, CustomAclDetails> customAcls = desiredStateFile.getCustomUserAcls().get(name);
                customAcls.forEach((aclName, customAcl) -> {
                    AclDetails.Builder aclDetails = AclDetails.fromCustomAclDetails(customAcl);
                    aclDetails.setPrincipal(String.format("User:%s", serviceAccountId));
                    putAclIfAbsent(desiredState, name, index, aclDetails.build());
                });
            }
        });
    }

    private void generateServiceAcls(DesiredState.Builder desiredState, DesiredStateFile desiredStateFile) {
        desiredStateFile.getServices().forEach((name, service) -> {
            AtomicInteger index = new AtomicInteger(0);
            service.getAcls(buildGetAclOptions(name)).forEach(aclDetails -> {
                putAclIfAbsent(desiredState, name, index, buildAclDetails(name, aclDetails));
            });

            if (desiredStateFile.getCustomServiceAcls().containsKey(name)) {
                Map<String, CustomAclDetails> customAcls = desiredStateFile.getCustomServiceAcls().get(name);
                customAcls.forEach((aclName, customAcl) -> {
                    AclDetails.Builder aclDetails = AclDetails.fromCustomAclDetails(customAcl);
                    aclDetails.setPrincipal(customAcl.getPrincipal().orElseThrow(() ->
                            new MissingConfigurationException(String.format("Missing principal for custom service ACL %s", aclName))));
                    putAclIfAbsent(desiredState, name, index, aclDetails.build());
                });
            }
        });
    }

    private void generateUserAcls(DesiredState.Builder desiredState, DesiredStateFile desiredStateFile) {
        desiredStateFile.getUsers().forEach((name, user) -> {
            AtomicInteger index = new AtomicInteger(0);
            String userPrincipal = user.getPrincipal()
                    .orElseThrow(() -> new MissingConfigurationException(String.format("Missing principal for user %s", name)));

            user.getRoles().forEach(role -> {
                List<AclDetails.Builder> acls = roleService.getAcls(role, userPrincipal);
                acls.forEach(acl -> putAclIfAbsent(desiredState, name, index, acl.build()));
            });

            if (desiredStateFile.getCustomUserAcls().containsKey(name)) {
                Map<String, CustomAclDetails> customAcls = desiredStateFile.getCustomUserAcls().get(name);
                customAcls.forEach((aclName, customAcl) -> {
                    AclDetails.Builder aclDetails = AclDetails.fromCustomAclDetails(customAcl);
                    aclDetails.setPrincipal(customAcl.getPrincipal().orElse(userPrincipal));
                    putAclIfAbsent(desiredState, name, index, aclDetails.build());
                });
            }
        });
    }

    private void putAclIfAbsent(DesiredState.Builder desiredState, String name, AtomicInteger index, AclDetails aclDetails) {
        if (!desiredState.getAcls().containsValue(aclDetails)) {
            desiredState.putAcls(String.format("%s-%s", name, index.getAndIncrement()), aclDetails);
        }
    }

    private AclDetails buildAclDetails(String service, AclDetails.Builder aclDetails) {
        try {
            return aclDetails.build();
        } catch (IllegalStateException ex) {
            throw new MissingConfigurationException(String.format("%s for service: %s", ex.getMessage(), service));
        }
    }

    private List<String> getPrefixedTopicsToIgnore(DesiredStateFile desiredStateFile) {
        List<String> topics = new ArrayList<>();
        try {
            topics.addAll(desiredStateFile.getSettings().get().getTopics().get().getBlacklist().get().getPrefixed());
        } catch (NoSuchElementException ex) {
            // Do nothing, no blacklist exists
        }
        desiredStateFile.getServices().forEach((name, service) -> {
            if (service instanceof KafkaStreamsService) {
                KafkaStreamsService streamsService = (KafkaStreamsService) service;
                topics.add(streamsService.getApplicationId().orElse(name));
            }
        });
        return topics;
    }

    private List<String> getPrefixedTopicsToManage(DesiredStateFile desiredStateFile) {
        List<String> topics = new ArrayList<>();
        try {
            topics.addAll(desiredStateFile.getSettings().get().getTopics().get().getWhitelist().get().getPrefixed());
        } catch (NoSuchElementException ex) {
            // Do nothing, no whitelist exists
        }
        return topics;
    }

    private GetAclOptions buildGetAclOptions(String serviceName) {
        return new GetAclOptions.Builder().setServiceName(serviceName).setDescribeAclEnabled(describeAclEnabled).build();
    }

    private void validateCustomAcls(DesiredStateFile desiredStateFile) {
        desiredStateFile.getCustomServiceAcls().forEach((service, details) -> {
            if (!desiredStateFile.getServices().containsKey(service)) {
                throw new ValidationException(String.format("customServiceAcls references undefined service: %s", service));
            }
            try {
                details.values().forEach(CustomAclDetails::validate);
            } catch (InvalidAclDefinitionException ex) {
                String message = String.format("Custom ACL definition for service '%s' is invalid for field '%s'. Allowed values: [%s]", service, ex.getField(), String.join(", ", ex.getAllowedValues()));
                throw new ValidationException(message);
            }
        });

        desiredStateFile.getCustomUserAcls().forEach((service, details) -> {
            if (!desiredStateFile.getUsers().containsKey(service)) {
                throw new ValidationException(String.format("customUserAcls references undefined user: %s", service));
            }
            try {
                details.values().forEach(CustomAclDetails::validate);
            } catch (InvalidAclDefinitionException ex) {
                String message = String.format("Custom ACL definition for user '%s' is invalid for field '%s'. Allowed values: [%s]", service, ex.getField(), String.join(", ", ex.getAllowedValues()));
                throw new ValidationException(message);
            }
        });
    }

    private void validateTopics(DesiredStateFile desiredStateFile) {
        Optional<Integer> defaultPartitions = StateUtil.fetchPartitions(desiredStateFile);
        Optional<Integer> defaultReplication = StateUtil.fetchReplication(desiredStateFile);
        validateTopicFilters(desiredStateFile);

        if (defaultPartitions.isPresent() && defaultPartitions.get() < 1) {
            throw new ValidationException("The default partition count must be a positive integer.");
        }
        if (defaultReplication.isPresent() && defaultReplication.get() < 1) {
            throw new ValidationException("The default replication factor must be a positive integer.");
        }

        desiredStateFile.getTopics().forEach((name, details) -> {
            if (!details.getPartitions().isPresent() && !defaultPartitions.isPresent()) {
                throw new ValidationException(String.format("Not set: [partitions] in state file definition: topics -> %s", name));
            }

            if (details.getPartitions().isPresent() && details.getPartitions().get() < 1) {
                throw new ValidationException(String.format("The topic '%s' must define partitions >= 1.", name));
            }

            if (!details.getReplication().isPresent() && !defaultReplication.isPresent()) {
                throw new ValidationException(String.format("Not set: [replication] in state file definition: topics -> %s", name));
            }

            if (details.getReplication().isPresent() && details.getReplication().get() < 1) {
                throw new ValidationException(String.format("The topic '%s' must define replication >= 1.", name));
            }
        });
    }

    private void validateTopicFilters(DesiredStateFile desiredStateFile) {
        List<String> blacklistPrefixes = new ArrayList<>();
        List<String> whitelistPrefixes = new ArrayList<>();

        try {
            blacklistPrefixes.addAll(desiredStateFile.getSettings().get().getTopics().get().getBlacklist().get().getPrefixed());
        } catch (NoSuchElementException ex) {
            // Do nothing, no blacklist exists
        }

        try {
            whitelistPrefixes.addAll(desiredStateFile.getSettings().get().getTopics().get().getWhitelist().get().getPrefixed());
        } catch (NoSuchElementException ex) {
            // Do nothing, no whitelist exists
        }

        if (!blacklistPrefixes.isEmpty() && !whitelistPrefixes.isEmpty()) {
            throw new ValidationException("Topic whitelist and blacklist are mutually exclusive.");
        }

        if (!whitelistPrefixes.isEmpty()) {
            desiredStateFile.getTopics().forEach((name, details) -> {
                boolean managed = whitelistPrefixes.stream().anyMatch(name::startsWith);
                if (!managed) {
                    throw new ValidationException(String.format("The topic '%s' does not match any whitelisted prefix.", name));
                }
            });
        }
    }

    private boolean isConfluentCloudEnabled(DesiredStateFile desiredStateFile) {
        if (desiredStateFile.getSettings().isPresent() && desiredStateFile.getSettings().get().getCcloud().isPresent()) {
            return desiredStateFile.getSettings().get().getCcloud().get().isEnabled();
        }
        return false;
    }

    private ObjectMapper initializeObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.registerModule(new Jdk8Module());
        return objectMapper;
    }

    private void initializeLogger(boolean verbose) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger kafka = (Logger) LoggerFactory.getLogger("org.apache.kafka");
        if (verbose) {
            root.setLevel(Level.INFO);
            kafka.setLevel(Level.WARN);
        } else {
            root.setLevel(Level.WARN);
            kafka.setLevel(Level.OFF);
        }
    }
}
