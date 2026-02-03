package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentListItem {

    public String object; // "assistant"
    public String id;
    public String name;
    public String description;
    public String model;
    public String instructions;

    @JsonProperty("created_at")
    public Long createdAt;

    public List<AgentTool> tools;

    @JsonProperty("tool_resources")
    public JsonNode toolResources;

    public Double temperature;

    @JsonProperty("top_p")
    public Double topP;

    @JsonProperty("response_format")
    public JsonNode responseFormat;

    public Map<String, String> metadata;

    // Deprecated/unused: older API shape had versions nesting
    public Versions versions;

    public AzureAgentDescriptor toDescriptor() {
        // New flat API shape (current)
        if (model != null || instructions != null || tools != null) {
            return new AzureAgentDescriptor(
                    id,
                    name,
                    description,
                    createdAt != null ? createdAt : 0L,
                    model,
                    instructions,
                    null, // raiPolicyName extracted from tools if present
                    tools != null ? tools : Collections.emptyList(),
                    metadata != null ? metadata : Collections.emptyMap(),
                    temperature,
                    topP,
                    responseFormat != null ? responseFormat.toString() : null,
                    toolResources != null ? toolResources.toString() : null,
                    extractConnectedAgentIds(tools)
            );
        }

        // Fallback: old versioned API shape (if still used)
        if (versions != null && versions.getLatest() != null) {
            AgentVersion latest = versions.getLatest();
            AgentDefinition def = latest.getDefinition();

            String modelFromDef = (def != null) ? def.getModel() : null;
            String instructionsFromDef = (def != null) ? def.getInstructions() : null;
            String raiPolicy = (def != null && def.getRaiConfig() != null)
                    ? def.getRaiConfig().getRaiPolicyName()
                    : null;
            List<AgentTool> toolsFromDef = (def != null && def.getTools() != null)
                    ? def.getTools()
                    : Collections.emptyList();

            Map<String, String> metadataFromVersion =
                    (latest.getMetadata() != null) ? latest.getMetadata() : Collections.emptyMap();

            long createdAtFromVersion = (latest.getCreatedAt() != null) ? latest.getCreatedAt() : 0L;

            return new AzureAgentDescriptor(
                    id,
                    name,
                    latest.getDescription(),
                    createdAtFromVersion,
                    modelFromDef,
                    instructionsFromDef,
                    raiPolicy,
                    toolsFromDef,
                    metadataFromVersion,
                    null, // temperature not in versioned response
                    null, // topP not in versioned response
                    null, // responseFormat not in versioned response
                    null, // toolResources not in versioned response
                    extractConnectedAgentIds(toolsFromDef)
            );
        }

        // Minimal mapping (no data available)
        return new AzureAgentDescriptor(
                id,
                name,
                null,
                0L,
                null,
                null,
                null,
                Collections.emptyList(),
                Collections.emptyMap(),
                null,
                null,
                null,
                null,
                Collections.emptyList()
        );
    }

    /**
     * Extracts agent IDs from tools where type == "connected_agent".
     * Each such tool has a "connected_agent" key in its config map
     * containing a nested map with "id", "name", "description".
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractConnectedAgentIds(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> ids = new ArrayList<>();
        for (AgentTool tool : tools) {
            if (!"connected_agent".equals(tool.getType())) {
                continue;
            }
            Object raw = tool.getConfig().get("connected_agent");
            if (raw instanceof Map) {
                Object id = ((Map<String, Object>) raw).get("id");
                if (id instanceof String && !((String) id).isEmpty()) {
                    ids.add((String) id);
                }
            }
        }
        return ids;
    }
}