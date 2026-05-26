package com.devshawn.kafka.gitops.domain.state;

import com.devshawn.kafka.gitops.exception.InvalidAclDefinitionException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourceType;
import org.inferred.freebuilder.FreeBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@FreeBuilder
@JsonDeserialize(builder = CustomAclDetails.Builder.class)
public abstract class CustomAclDetails {
    private static final List<String> DEFAULT_EXCLUDED_ENUM_VALUES = List.of("ANY", "UNKNOWN");
    private static final List<String> PATTERN_EXCLUDED_ENUM_VALUES = List.of("ANY", "UNKNOWN", "MATCH");

    public abstract String getName();

    public abstract String getType();

    public abstract String getPattern();

    public abstract Optional<String> getPrincipal();

    public abstract String getHost();

    public abstract String getOperation();

    public abstract String getPermission();

    public void validate() {
        validateEnum(ResourceType.class, getType(), "type");
        validateEnum(PatternType.class, getPattern(), "pattern", PATTERN_EXCLUDED_ENUM_VALUES);
        validateEnum(AclOperation.class, getOperation(), "operation");
        validateEnum(AclPermissionType.class, getPermission(), "permission");
    }

    private <E extends Enum<E>> void validateEnum(Class<E> enumData, String value, String field) {
        validateEnum(enumData, value, field, DEFAULT_EXCLUDED_ENUM_VALUES);
    }

    private <E extends Enum<E>> void validateEnum(Class<E> enumData, String value, String field, List<String> excludedValues) {
        List<String> allowedValues = Arrays.stream(enumData.getEnumConstants())
                .map(Enum::name)
                .filter(it -> !excludedValues.contains(it))
                .collect(Collectors.toList());
        if (!allowedValues.contains(value)) {
            throw new InvalidAclDefinitionException(field, value, allowedValues);
        }
    }

    public static class Builder extends CustomAclDetails_Builder {
        public Builder() {
            setPermission(AclPermissionType.ALLOW.name());
            setHost("*");
        }
    }
}
