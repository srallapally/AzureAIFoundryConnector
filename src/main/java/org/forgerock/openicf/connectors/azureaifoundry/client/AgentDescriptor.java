package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDescriptor {

    public String kind; // "prompt"
    public RaiConfig rai_config;
    public String model;
    public String instructions;
    public List<AgentTool> tools;

    public String getKind() {
        return kind;
    }

    public RaiConfig getRaiConfig() {
        return rai_config;
    }

    public String getModel() {
        return model;
    }

    public String getInstructions() {
        return instructions;
    }

    public List<AgentTool> getTools() {
        return tools;
    }
}
