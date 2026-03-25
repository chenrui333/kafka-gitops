package com.devshawn.kafka.gitops.domain.state;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.inferred.freebuilder.FreeBuilder;

@FreeBuilder
@JsonDeserialize(builder = SchemaReference.Builder.class)
public interface SchemaReference {

    String getName();

    String getSubject();

    int getVersion();

    class Builder extends SchemaReference_Builder {
    }
}
