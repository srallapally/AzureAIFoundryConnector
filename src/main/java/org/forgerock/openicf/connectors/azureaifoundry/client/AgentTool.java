package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTool {

    public String type;

    private final Map<String, Object> config = new HashMap<>();

    @JsonAnySetter
    public void put(String key, Object value) {
        config.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, Object> any() {
        return config;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
