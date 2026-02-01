package org.forgerock.openicf.connectors.azureaifoundry.client;

public class AzureGuardrailDescriptor {

    private final String id;
    private final String raiPolicyName;
    private final java.util.List<String> agents;

    // Optionally keep raw definition if you want for debug / future use
    private final com.fasterxml.jackson.databind.JsonNode definition;

    public AzureGuardrailDescriptor(String id,
                                    String raiPolicyName,
                                    java.util.List<String> agents,
                                    com.fasterxml.jackson.databind.JsonNode definition) {
        this.id = id;
        this.raiPolicyName = raiPolicyName;
        this.agents = agents == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(agents));
        this.definition = definition;
    }

    public String getId() {
        return id;
    }

    public String getRaiPolicyName() {
        return raiPolicyName;
    }

    public java.util.List<String> getAgents() {
        return agents;
    }

    public com.fasterxml.jackson.databind.JsonNode getDefinition() {
        return definition;
    }

    // Optional convenience: last segment of raiPolicyName
    public String getShortName() {
        if (raiPolicyName == null) {
            return null;
        }
        int idx = raiPolicyName.lastIndexOf('/');
        return idx >= 0 ? raiPolicyName.substring(idx + 1) : raiPolicyName;
    }
}
