package com.devshawn.kafka.gitops.domain.plan;

import com.devshawn.kafka.gitops.domain.state.SchemaReference;
import com.devshawn.kafka.gitops.enums.PlanAction;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.inferred.freebuilder.FreeBuilder;

import java.util.List;

@FreeBuilder
@JsonDeserialize(builder = SchemaPlan.Builder.class)
public interface SchemaPlan {

    String getSubject();

    String getSchemaType();

    String getSchema();

    List<SchemaReference> getReferences();

    PlanAction getAction();

    class Builder extends SchemaPlan_Builder {
    }
}
