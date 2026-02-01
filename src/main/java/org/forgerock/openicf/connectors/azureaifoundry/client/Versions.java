package org.forgerock.openicf.connectors.azureaifoundry.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Versions {
    public AgentVersion latest;

    public AgentVersion getLatest() {
        return latest;
    }
}
