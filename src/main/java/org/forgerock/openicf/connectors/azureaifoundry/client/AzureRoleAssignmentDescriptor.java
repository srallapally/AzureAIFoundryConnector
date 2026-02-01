package org.forgerock.openicf.connectors.azureaifoundry.client;

public class AzureRoleAssignmentDescriptor {

    private final String id;
    private final String scope;
    private final String principalId;
    private final String roleDefinitionId;

    public AzureRoleAssignmentDescriptor(String id,
                                         String scope,
                                         String principalId,
                                         String roleDefinitionId) {
        this.id = id;
        this.scope = scope;
        this.principalId = principalId;
        this.roleDefinitionId = roleDefinitionId;
    }

    public String getId() {
        return id;
    }

    public String getScope() {
        return scope;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getRoleDefinitionId() {
        return roleDefinitionId;
    }
}
