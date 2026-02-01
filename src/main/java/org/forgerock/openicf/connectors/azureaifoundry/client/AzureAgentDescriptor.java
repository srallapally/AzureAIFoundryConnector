package org.forgerock.openicf.connectors.azureaifoundry.client;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AzureAgentDescriptor {

    private final String id;
    private final String name;
    private final String description;
    private final long createdAtEpochSeconds;
    private final String model;
    private final String instructions;
    private final String raiPolicyName;
    private final List<AgentTool> tools;
    private final Map<String, String> metadata;

    public AzureAgentDescriptor(String id,
                                String name,
                                String description,
                                long createdAtEpochSeconds,
                                String model,
                                String instructions,
                                String raiPolicyName,
                                List<AgentTool> tools,
                                Map<String, String> metadata) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAtEpochSeconds = createdAtEpochSeconds;
        this.model = model;
        this.instructions = instructions;
        this.raiPolicyName = raiPolicyName;
        this.tools = (tools != null) ? tools : Collections.emptyList();
        this.metadata = (metadata != null) ? metadata : Collections.emptyMap();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public long getCreatedAtEpochSeconds() {
        return createdAtEpochSeconds;
    }

    public String getModel() {
        return model;
    }

    public String getInstructions() {
        return instructions;
    }

    public String getRaiPolicyName() {
        return raiPolicyName;
    }

    public List<AgentTool> getTools() {
        return tools;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getVersionOrDefault(String fallback) {
        // No explicit version exposed yet; use fallback.
        return fallback;
    }
}
