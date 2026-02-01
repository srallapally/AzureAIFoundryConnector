package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentVersion {

    public Map<String, String> metadata;
    public String object; // "agent.version"
    public String id;     // "agentName:version"
    public String name;
    public String version;
    public String description;
    public Long created_at;
    public AgentDefinition definition;

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getObject() {
        return object;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public Long getCreatedAt() {
        return created_at;
    }

    public AgentDefinition getDefinition() {
        return definition;
    }
}
