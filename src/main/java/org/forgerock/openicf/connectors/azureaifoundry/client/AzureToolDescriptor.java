package org.forgerock.openicf.connectors.azureaifoundry.client;

public class AzureToolDescriptor {

    private final String id;
    private final String name;
    private final String type;
    private final String description;
    private final String endpoint;
    private final String definition;

    public AzureToolDescriptor(String id,
                               String name,
                               String type,
                               String description,
                               String endpoint,
                               String definition) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.description = description;
        this.endpoint = endpoint;
        this.definition = definition;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getDefinition() {
        return definition;
    }
}