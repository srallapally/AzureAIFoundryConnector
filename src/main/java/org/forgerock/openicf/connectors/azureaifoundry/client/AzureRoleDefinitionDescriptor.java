package org.forgerock.openicf.connectors.azureaifoundry.client;

import java.util.List;

public class AzureRoleDefinitionDescriptor {

    private final String id;
    private final String name;
    private final List<String> allowedActions;

    public AzureRoleDefinitionDescriptor(String id,
                                         String name,
                                         List<String> allowedActions) {
        this.id = id;
        this.name = name;
        this.allowedActions = allowedActions;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getAllowedActions() {
        return allowedActions;
    }
}
