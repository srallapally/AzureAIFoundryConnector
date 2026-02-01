package org.forgerock.openicf.connectors.azureaifoundry.client;

public class AzurePrincipalDescriptor {

    private final String objectId;
    private final String displayName;
    private final String userPrincipalName;
    private final String principalType;

    public AzurePrincipalDescriptor(String objectId,
                                    String displayName,
                                    String userPrincipalName,
                                    String principalType) {
        this.objectId = objectId;
        this.displayName = displayName;
        this.userPrincipalName = userPrincipalName;
        this.principalType = principalType;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getUserPrincipalName() {
        return userPrincipalName;
    }

    public String getPrincipalType() {
        return principalType;
    }
}