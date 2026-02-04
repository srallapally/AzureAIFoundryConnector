package org.forgerock.openicf.connectors.azureaifoundry.client;

import java.util.Collections;
import java.util.List;

/**
 * Descriptor for an identity binding between an agent and a principal.
 * Represents who (principal) has access to which agent with what permissions.
 */
public class AzureIdentityBindingDescriptor {

    private final String id;
    private final String agentId;
    private final String agentVersion;
    private final String scope;
    private final String scopeResourceId;
    private final String principalId;
    private final String principalType;
    private final String roleDefinitionId;
    private final String roleName;
    private final List<String> permissions;

    public AzureIdentityBindingDescriptor(String id,
                                          String agentId,
                                          String agentVersion,
                                          String scope,
                                          String scopeResourceId,
                                          String principalId,
                                          String principalType,
                                          String roleDefinitionId,
                                          String roleName,
                                          List<String> permissions) {
        this.id = id;
        this.agentId = agentId;
        this.agentVersion = agentVersion != null ? agentVersion : "latest";
        this.scope = scope;
        this.scopeResourceId = scopeResourceId;
        this.principalId = principalId;
        this.principalType = principalType;
        this.roleDefinitionId = roleDefinitionId;
        this.roleName = roleName;
        this.permissions = permissions != null
                ? Collections.unmodifiableList(permissions)
                : Collections.emptyList();
    }

    public String getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public String getScope() {
        return scope;
    }

    public String getScopeResourceId() {
        return scopeResourceId;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public String getRoleDefinitionId() {
        return roleDefinitionId;
    }

    public String getRoleName() {
        return roleName;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    /**
     * Get the kind/type of binding based on principal type.
     * Maps to ATTR_KIND in the schema.
     */
    public String getKind() {
        if (principalType == null) {
            return "DIRECT";
        }
        switch (principalType) {
            case "Group":
                return "GROUP";
            case "ManagedIdentity":
                return "MANAGED_IDENTITY";
            case "ServicePrincipal":
                return "SERVICE_PRINCIPAL";
            case "User":
            default:
                return "DIRECT";
        }
    }
}