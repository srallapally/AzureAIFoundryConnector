package org.forgerock.openicf.connectors.azureaifoundry.client;

public class AzureAgentRelations {

    private final java.util.List<String> toolIds;
    private final java.util.List<String> knowledgeBaseIds;
    private final java.util.List<String> guardrailIds;

    public AzureAgentRelations(java.util.List<String> toolIds,
                               java.util.List<String> knowledgeBaseIds,
                               java.util.List<String> guardrailIds) {
        this.toolIds = toolIds == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(toolIds));
        this.knowledgeBaseIds = knowledgeBaseIds == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(knowledgeBaseIds));
        this.guardrailIds = guardrailIds == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(guardrailIds));
    }

    public java.util.List<String> getToolIds() {
        return toolIds;
    }

    public java.util.List<String> getKnowledgeBaseIds() {
        return knowledgeBaseIds;
    }

    public java.util.List<String> getGuardrailIds() {
        return guardrailIds;
    }
}
