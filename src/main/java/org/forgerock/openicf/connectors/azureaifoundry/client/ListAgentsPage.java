package org.forgerock.openicf.connectors.azureaifoundry.client;

import java.util.List;

public class ListAgentsPage {

    private final List<AzureAgentDescriptor> agents;
    private final String continuationToken;

    public ListAgentsPage(List<AzureAgentDescriptor> agents, String continuationToken) {
        this.agents = agents;
        this.continuationToken = continuationToken;
    }

    public List<AzureAgentDescriptor> getAgents() {
        return agents;
    }

    public String getContinuationToken() {
        return continuationToken;
    }
}
