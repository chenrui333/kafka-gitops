package com.devshawn.kafka.gitops.domain.state.settings;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.inferred.freebuilder.FreeBuilder;

import java.util.List;

@FreeBuilder
@JsonDeserialize(builder = SettingsTopicsWhitelist.Builder.class)
public interface SettingsTopicsWhitelist {

    List<String> getPrefixed();

    class Builder extends SettingsTopicsWhitelist_Builder {
    }
}
