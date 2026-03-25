package com.devshawn.kafka.gitops.domain.state;

import com.devshawn.kafka.gitops.exception.ValidationException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.inferred.freebuilder.FreeBuilder;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@FreeBuilder
@JsonDeserialize(builder = SchemaDetails.Builder.class)
public abstract class SchemaDetails {

    private static final List<String> SUPPORTED_TYPES = List.of("AVRO", "JSON", "PROTOBUF");

    public abstract Optional<String> getRelativeLocation();

    public abstract Optional<String> getSchema();

    public abstract Optional<String> getType();

    public abstract List<String> getSubjects();

    public abstract List<SchemaReference> getReferences();

    public void validate() {
        if (getRelativeLocation().isPresent() == getSchema().isPresent()) {
            throw new ValidationException("must define exactly one of relativeLocation or schema");
        }

        if (!SUPPORTED_TYPES.contains(getResolvedType())) {
            throw new ValidationException(String.format("defines unsupported type '%s'. Allowed values: [%s]",
                    getResolvedType(),
                    String.join(", ", SUPPORTED_TYPES)));
        }
    }

    public String getResolvedType() {
        return getType().orElse("AVRO").toUpperCase(Locale.ROOT);
    }

    public static class Builder extends SchemaDetails_Builder {
    }
}
