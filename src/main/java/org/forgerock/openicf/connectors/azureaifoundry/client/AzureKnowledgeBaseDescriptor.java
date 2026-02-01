package org.forgerock.openicf.connectors.azureaifoundry.client;

public class AzureKnowledgeBaseDescriptor {

    private final String id;
    private final String name;
    private final String sourceType;
    private final String connectionRef;
    private final String status;

    public AzureKnowledgeBaseDescriptor(String id,
                                        String name,
                                        String sourceType,
                                        String connectionRef,
                                        String status) {
        this.id = id;
        this.name = name;
        this.sourceType = sourceType;
        this.connectionRef = connectionRef;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getConnectionRef() {
        return connectionRef;
    }

    public String getStatus() {
        return status;
    }
}