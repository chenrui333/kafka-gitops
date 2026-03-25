package com.devshawn.kafka.gitops.domain.state.settings;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.inferred.freebuilder.FreeBuilder;

import java.util.Optional;

@FreeBuilder
@JsonDeserialize(builder = SettingsSchemaRegistry.Builder.class)
public interface SettingsSchemaRegistry {

    String getUrl();

    Optional<String> getUsername();

    Optional<String> getPassword();

    class Builder extends SettingsSchemaRegistry_Builder {
    }
}
