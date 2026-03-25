package com.devshawn.kafka.gitops.domain.plan;

import com.devshawn.kafka.gitops.enums.PlanAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.inferred.freebuilder.FreeBuilder;

import java.util.List;

@FreeBuilder
@JsonDeserialize(builder = DesiredPlan.Builder.class)
public interface DesiredPlan {

    List<TopicPlan> getTopicPlans();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    List<SchemaPlan> getSchemaPlans();

    List<AclPlan> getAclPlans();

    default DesiredPlan toChangesOnlyPlan() {
        DesiredPlan.Builder builder = new DesiredPlan.Builder();
        getTopicPlans().stream().filter(it -> !it.getAction().equals(PlanAction.NO_CHANGE)).map(TopicPlan::toChangesOnlyPlan).forEach(builder::addTopicPlans);
        getSchemaPlans().stream().filter(it -> !it.getAction().equals(PlanAction.NO_CHANGE)).forEach(builder::addSchemaPlans);
        getAclPlans().stream().filter(it -> !it.getAction().equals(PlanAction.NO_CHANGE)).forEach(builder::addAclPlans);
        return builder.build();
    }

    class Builder extends DesiredPlan_Builder {
    }
}
