package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentListItem {

    public String object; // "agent"
    public String id;
    public String name;
    public Versions versions;

    public AzureAgentDescriptor toDescriptor() {
        if (versions == null || versions.getLatest() == null) {
            // minimal mapping
            return new AzureAgentDescriptor(
                    id,
                    name,
                    null,
                    0L,
                    null,
                    null,
                    null,
                    Collections.emptyList(),
                    Collections.emptyMap()
            );
        }

        AgentVersion latest = versions.getLatest();
        AgentDefinition def = latest.getDefinition();

        String model = (def != null) ? def.getModel() : null;
        String instructions = (def != null) ? def.getInstructions() : null;
        String raiPolicy = (def != null && def.getRaiConfig() != null)
                ? def.getRaiConfig().getRaiPolicyName()
                : null;
        List<AgentTool> tools = (def != null && def.getTools() != null)
                ? def.getTools()
                : Collections.emptyList();

        Map<String, String> metadata =
                (latest.getMetadata() != null) ? latest.getMetadata() : Collections.emptyMap();

        long createdAt = (latest.getCreatedAt() != null) ? latest.getCreatedAt() : 0L;

        return new AzureAgentDescriptor(
                id,
                name,
                latest.getDescription(),
                createdAt,
                model,
                instructions,
                raiPolicy,
                tools,
                metadata
        );
    }
}
