package org.forgerock.openicf.connectors.azureaifoundry.operations;

import org.forgerock.openicf.connectors.azureaifoundry.AzureAIFoundryConnection;
import org.forgerock.openicf.connectors.azureaifoundry.client.*;
import org.forgerock.openicf.connectors.azureaifoundry.utils.AzureAIFoundryConstants;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.identityconnectors.framework.common.objects.filter.Filter;
import static org.identityconnectors.framework.common.objects.Name.NAME;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * CRUD-style service for the Azure AI Foundry connector.
 *
 * For v1 this is strictly read-only and focuses on inventory:
 *  - Agents (AgentInventory / core attributes)
 *  - Placeholders for tools, knowledge bases, guardrails, identity bindings
 *    to keep parity with AwsBedrockCrudService.
 */
public class AzureAIFoundryCrudService {

    private static final Log LOG = Log.getLog(AzureAIFoundryCrudService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AzureAIFoundryConnection connection;
    private final AzureAIFoundryClient client;
    // Reserved for future RBAC-based identity binding discovery
    private volatile Map<String, List<AgentIdentityBinding>> bindingsByKey =
            new ConcurrentHashMap<>();
    private volatile Instant bindingsLoadedAt = Instant.EPOCH;
    private static final long BINDINGS_CACHE_TTL_SECONDS = 300L; // 5 minutes

    public AzureAIFoundryCrudService(AzureAIFoundryConnection connection) {
        this.connection = connection;
        this.client = connection.getClient();
    }

    // =================================================================
    // Search operations
    // =================================================================

    /**
     * Search agents.
     *
     * For now, this ignores "query" and returns all agents discovered via
     * listAgents(). The IGA layer can post-filter as needed.
     */
    public void searchAgents(ObjectClass objectClass,
                             Filter query,
                             ResultsHandler handler,
                             OperationOptions options) {
        List<AzureAgentDescriptor> agents =
                client.listAgents(connection.getAgentBasePath(), connection.getApiVersion());
        for (AzureAgentDescriptor agent : agents) {
            System.out.println(agent.toString());
            ConnectorObject obj = toAgentConnectorObject(objectClass, agent);
            if (obj == null) {
                continue;
            }
            if (!handler.handle(obj)) {
                LOG.ok("Handler requested to stop processing agents.");
                return;
            }
        }
    }

    public void searchGuardrails(ObjectClass objectClass,
                                 Filter query,
                                 ResultsHandler handler,
                                 OperationOptions options) {
        LOG.ok("searchGuardrails called for OC {0}", objectClass);

        if (!AzureAIFoundryConstants.OC_GUARDRAIL.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for searchGuardrails: " +
                    objectClass.getObjectClassValue());
        }

        java.util.List<AzureGuardrailDescriptor> guardrails = client.listAllGuardrails();
        if (guardrails == null || guardrails.isEmpty()) {
            LOG.ok("No guardrails found in tools inventory.");
            return;
        }

        // Optional simple filter support: EqualsFilter on __UID__ or __NAME__
        String matchUid = null;
        String matchName = null;

        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            if (attr != null && attr.getName() != null && !attr.getValue().isEmpty()) {
                String val = String.valueOf(attr.getValue().get(0));
                if (Uid.NAME.equalsIgnoreCase(attr.getName()) ) {
                    matchUid = val;
                } else if (NAME.equalsIgnoreCase(attr.getName())) {
                    matchName = val;
                }
            }
        }

        for (AzureGuardrailDescriptor gr : guardrails) {
            ConnectorObject co = toGuardrailConnectorObject(objectClass, gr);
            if (co == null) {
                continue;
            }

            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }

            if (!handler.handle(co)) {
                LOG.ok("Handler requested to stop searchGuardrails iteration.");
                break;
            }
        }
    }

    public void searchKnowledgeBases(ObjectClass objectClass,
                                     Filter query,
                                     ResultsHandler handler,
                                     OperationOptions options) {
        LOG.ok("searchKnowledgeBases called for OC {0}", objectClass);

        if (!AzureAIFoundryConstants.OC_KNOWLEDGE_BASE.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for searchKnowledgeBases: " +
                    objectClass.getObjectClassValue());
        }

        List<AzureKnowledgeBaseDescriptor> kbs = client.listAllKnowledgeBases();
        if (kbs == null || kbs.isEmpty()) {
            LOG.ok("No knowledge bases found in tools inventory.");
            return;
        }

        // Optional simple filter support: EqualsFilter on __UID__ or __NAME__
        String matchUid = null;
        String matchName = null;

        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            if (attr != null && attr.getName() != null && !attr.getValue().isEmpty()) {
                String val = String.valueOf(attr.getValue().get(0));
                if (Uid.NAME.equalsIgnoreCase(attr.getName()) ) {
                    matchUid = val;
                } else if (NAME.equalsIgnoreCase(attr.getName())) {
                    matchName = val;
                }
            }
        }

        for (AzureKnowledgeBaseDescriptor kb : kbs) {
            ConnectorObject co = toKnowledgeBaseConnectorObject(objectClass, kb);
            if (co == null) {
                continue;
            }

            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }

            if (!handler.handle(co)) {
                LOG.ok("Handler requested to stop searchKnowledgeBases iteration.");
                break;
            }
        }
    }


    /**
     * Tool / action group search backed entirely by the tools inventory.
     *
     * OC_TOOL ("agentTool") is the only supported object class here.
     */
    public void searchTools(ObjectClass objectClass,
                            Filter query,
                            ResultsHandler handler,
                            OperationOptions options) {
        LOG.ok("searchTools called for OC {0}", objectClass);

        if (!AzureAIFoundryConstants.OC_TOOL.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for searchTools: " +
                    objectClass.getObjectClassValue());
        }

        List<AzureToolDescriptor> tools = client.listAllTools();
        if (tools == null || tools.isEmpty()) {
            LOG.ok("No tools found in tools inventory.");
            return;
        }

        // Optional simple filter support: EqualsFilter on __UID__ or __NAME__
        String matchUid = null;
        String matchName = null;

        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            String attrName = attr.getName();
            String value = AttributeUtil.getAsStringValue(attr);

            if (Uid.NAME.equals(attrName)) {
                matchUid = value;
            } else if (Name.NAME.equals(attrName)) {
                matchName = value;
            }
        }

        for (AzureToolDescriptor tool : tools) {
            ConnectorObject co = toToolConnectorObject(objectClass, tool);
            if (co == null) {
                continue;
            }

            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }

            if (!handler.handle(co)) {
                LOG.ok("Handler requested to stop searchTools iteration.");
                break;
            }
        }
    }

    private ConnectorObject toToolConnectorObject(ObjectClass objectClass,
                                                  AzureToolDescriptor tool) {
        if (tool == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__ – tool id from inventory
        String id = tool.getId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        b.setUid(new Uid(id));

        // __NAME__ – name or fallback to id
        String name = tool.getName() != null && !tool.getName().isEmpty()
                ? tool.getName()
                : id;
        b.setName(new Name(name));

        // Description
        if (tool.getDescription() != null && !tool.getDescription().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_DESCRIPTION,
                    tool.getDescription()));
        }

        // Type – no dedicated constant yet; expose as "toolType"
        if (tool.getType() != null && !tool.getType().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    "toolType",
                    tool.getType()));
        }

        // Endpoint – map to action group executor (consistent with Bedrock model)
        if (tool.getEndpoint() != null && !tool.getEndpoint().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_ACTION_GROUP_EXECUTOR_ARN,
                    tool.getEndpoint()));
        }

        // Definition/schema – map to schema URI
        if (tool.getDefinition() != null && !tool.getDefinition().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_ACTION_GROUP_SCHEMA_URI,
                    tool.getDefinition()));
        }

        return b.build();
    }

    private ConnectorObject toKnowledgeBaseConnectorObject(ObjectClass objectClass,
                                                           AzureKnowledgeBaseDescriptor kb) {
        if (kb == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__
        String id = kb.getId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        b.setUid(new Uid(id));

        // __NAME__
        String name = kb.getName();
        if (name == null || name.isEmpty()) {
            name = id;
        }
        b.setName(new Name(name));

        // Common attributes
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                "AZURE_AI_FOUNDRY"));

        // Knowledge base–specific attributes
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_KNOWLEDGE_BASE_ID,
                id));

        if (kb.getStatus() != null && !kb.getStatus().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_KNOWLEDGE_BASE_STATE,
                    kb.getStatus()));
        }

        // Optionally expose sourceType / connectionRef as free-form attributes
        if (kb.getSourceType() != null && !kb.getSourceType().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    "sourceType",
                    kb.getSourceType()));
        }
        if (kb.getConnectionRef() != null && !kb.getConnectionRef().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    "connectionRef",
                    kb.getConnectionRef()));
        }

        return b.build();
    }

    private ConnectorObject toGuardrailConnectorObject(ObjectClass objectClass,
                                                       AzureGuardrailDescriptor gr) {
        if (gr == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // UID
        String id = gr.getId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        b.setUid(new Uid(id));

        // NAME – use raiPolicyName or shortName if available
        String name = gr.getRaiPolicyName();
        if (name == null || name.isEmpty()) {
            name = gr.getShortName();
        }
        if (name == null || name.isEmpty()) {
            name = id;
        }
        b.setName(new Name(name));

        // Platform
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                "AZURE_AI_FOUNDRY"));

        // Guardrail-specific attributes
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_GUARDRAIL_ID,
                id));

        if (gr.getRaiPolicyName() != null) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_GUARDRAIL_RAI_POLICY_NAME,
                    gr.getRaiPolicyName()));
        }

        String shortName = gr.getShortName();
        if (shortName != null) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_GUARDRAIL_RAI_POLICY_SHORT_NAME,
                    shortName));
        }

        // Relationship preview: list of agent ids using this guardrail (Step 3 will define semantics)
        if (gr.getAgents() != null && !gr.getAgents().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_GUARDRAIL_AGENT_IDS,
                    gr.getAgents()));
        }

        return b.build();
    }

    /**
     * Search identity bindings (Agent ↔ Principal).
     */
    public void searchIdentityBindings(ObjectClass objectClass,
                                       Filter query,
                                       ResultsHandler handler,
                                       OperationOptions options) {
        LOG.ok("searchIdentityBindings called for OC {0}", objectClass);

        if (!AzureAIFoundryConstants.OC_IDENTITY_BINDING.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for searchIdentityBindings: " +
                    objectClass.getObjectClassValue());
        }

        List<AzureIdentityBindingDescriptor> bindings = client.listAllIdentityBindings();
        if (bindings == null || bindings.isEmpty()) {
            LOG.ok("No identity bindings found in tools inventory.");
            return;
        }

        // Optional simple filter support: EqualsFilter on __UID__ or __NAME__
        String matchUid = null;
        String matchName = null;

        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            if (attr != null && attr.getName() != null && !attr.getValue().isEmpty()) {
                String val = String.valueOf(attr.getValue().get(0));
                if (Uid.NAME.equalsIgnoreCase(attr.getName())) {
                    matchUid = val;
                } else if (NAME.equalsIgnoreCase(attr.getName())) {
                    matchName = val;
                }
            }
        }

        for (AzureIdentityBindingDescriptor binding : bindings) {
            ConnectorObject co = toIdentityBindingConnectorObject(objectClass, binding);
            if (co == null) {
                continue;
            }

            if (matchUid != null && !matchUid.equals(co.getUid().getUidValue())) {
                continue;
            }
            if (matchName != null && !matchName.equals(co.getName().getNameValue())) {
                continue;
            }

            if (!handler.handle(co)) {
                LOG.ok("Handler requested to stop searchIdentityBindings iteration.");
                break;
            }
        }
    }

    // =================================================================
    // Get-style operations
    // =================================================================

    /**
     * Get a single agent by UID.
     *
     * The UID value is currently just the agentId as returned by the
     * Azure Agent Service.
     */
    public ConnectorObject getAgent(ObjectClass objectClass,
                                    Uid uid,
                                    OperationOptions options) {
        AzureAgentDescriptor agent =
                client.getAgent(uid.getUidValue(), connection.getAgentBasePath(), connection.getApiVersion());
        if (agent == null) {
            return null;
        }
        return toAgentConnectorObject(objectClass, agent);
    }

    /**
     * Delete an agent by UID.
     */
    public void deleteAgent(ObjectClass objectClass, Uid uid, OperationOptions options) {
        if (uid == null || uid.getUidValue() == null || uid.getUidValue().isEmpty()) {
            throw new IllegalArgumentException("UID must not be null or empty for delete.");
        }

        String agentId = uid.getUidValue();
        LOG.ok("Deleting agent {0}", agentId);

        client.deleteAgent(agentId, connection.getAgentBasePath(), connection.getApiVersion());

        LOG.ok("Agent {0} deleted successfully.", agentId);
    }

    public ConnectorObject getGuardrail(ObjectClass objectClass,
                                        Uid uid,
                                        OperationOptions options) {
        LOG.ok("getGuardrail called for OC {0}, Uid {1}",
                objectClass, uid == null ? null : uid.getUidValue());

        if (!AzureAIFoundryConstants.OC_GUARDRAIL.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for getGuardrail: "
                    + objectClass.getObjectClassValue());
        }
        if (uid == null || uid.getUidValue() == null || uid.getUidValue().isEmpty()) {
            throw new IllegalArgumentException("Uid is required for getGuardrail");
        }

        String id = uid.getUidValue();
        List<AzureGuardrailDescriptor> guardrails = client.listAllGuardrails();
        if (guardrails == null || guardrails.isEmpty()) {
            LOG.ok("No guardrails found in tools inventory for getGuardrail.");
            return null;
        }

        for (AzureGuardrailDescriptor gr : guardrails) {
            if (id.equals(gr.getId())) {
                return toGuardrailConnectorObject(objectClass, gr);
            }
        }

        LOG.ok("Guardrail with id {0} not found in tools inventory.", id);
        return null;
    }


    public ConnectorObject getKnowledgeBase(ObjectClass objectClass,
                                            Uid uid,
                                            OperationOptions options) {
        LOG.ok("getKnowledgeBase called for OC {0}, Uid {1}",
                objectClass, uid == null ? null : uid.getUidValue());

        if (!AzureAIFoundryConstants.OC_KNOWLEDGE_BASE.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for getKnowledgeBase: "
                    + objectClass.getObjectClassValue());
        }
        if (uid == null || uid.getUidValue() == null || uid.getUidValue().isEmpty()) {
            throw new IllegalArgumentException("Uid is required for getKnowledgeBase");
        }

        String id = uid.getUidValue();
        List<AzureKnowledgeBaseDescriptor> kbs = client.listAllKnowledgeBases();
        if (kbs == null || kbs.isEmpty()) {
            LOG.ok("No knowledge bases found in tools inventory for getKnowledgeBase.");
            return null;
        }

        for (AzureKnowledgeBaseDescriptor kb : kbs) {
            if (id.equals(kb.getId())) {
                return toKnowledgeBaseConnectorObject(objectClass, kb);
            }
        }

        LOG.ok("Knowledge base with id {0} not found in tools inventory.", id);
        return null;
    }

    public ConnectorObject getTool(ObjectClass objectClass,
                                   Uid uid,
                                   OperationOptions options) {
        LOG.ok("getTool called for OC {0}, Uid {1}", objectClass, uid);

        if (!AzureAIFoundryConstants.OC_TOOL.equals(objectClass.getObjectClassValue())) {
            throw new IllegalArgumentException("Unsupported object class for getTool: "
                    + objectClass.getObjectClassValue());
        }

        if (uid == null || uid.getUidValue() == null || uid.getUidValue().isEmpty()) {
            return null;
        }

        String id = uid.getUidValue();

        List<AzureToolDescriptor> tools = client.listAllTools();
        if (tools == null || tools.isEmpty()) {
            LOG.ok("No tools found in tools inventory; getTool returning null.");
            return null;
        }

        for (AzureToolDescriptor tool : tools) {
            if (tool != null && id.equals(tool.getId())) {
                return toToolConnectorObject(objectClass, tool);
            }
        }

        return null;
    }


    // =================================================================
    // Identity binding cache helpers (stubs for future RBAC wiring)
    // =================================================================

    private Map<String, List<AgentIdentityBinding>> getBindingsCache() {
        // In a future version this will load / refresh bindings from Azure RBAC.
        return bindingsByKey;
    }

    // =================================================================
    // ConnectorObject mapping helpers
    // =================================================================

    private ConnectorObject toIdentityBindingConnectorObject(ObjectClass objectClass,
                                                             AzureIdentityBindingDescriptor binding) {
        if (binding == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        // __UID__ - binding ID
        String id = binding.getId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        b.setUid(new Uid(id));

        // __NAME__ - human-readable name (agent:principal format)
        String name = binding.getAgentId() + ":" + binding.getPrincipalId();
        b.setName(new Name(name));

        // Platform
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                "AZURE_AI_FOUNDRY"));

        // Agent reference
        if (binding.getAgentId() != null) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_AGENT_ID,
                    binding.getAgentId()));
        }

        if (binding.getAgentVersion() != null) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_AGENT_VERSION,
                    binding.getAgentVersion()));
        }

        // Kind (binding type)
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_KIND,
                binding.getKind()));

        // Principal info - pack as JSON string
        if (binding.getPrincipalId() != null) {
            try {
                Map<String, String> principalData = new java.util.HashMap<>();
                principalData.put("principalId", binding.getPrincipalId());
                if (binding.getPrincipalType() != null) {
                    principalData.put("principalType", binding.getPrincipalType());
                }
                if (binding.getRoleName() != null) {
                    principalData.put("roleName", binding.getRoleName());
                }
                if (binding.getRoleDefinitionId() != null) {
                    principalData.put("roleDefinitionId", binding.getRoleDefinitionId());
                }

                String principalJson = OBJECT_MAPPER.writeValueAsString(principalData);
                b.addAttribute(AttributeBuilder.build(
                        AzureAIFoundryConstants.ATTR_PRINCIPAL,
                        principalJson));
            } catch (JsonProcessingException e) {
                LOG.warn("Failed to serialize principal for binding {0}: {1}", id, e.getMessage());
            }
        }

        // Permissions (multi-valued)
        if (binding.getPermissions() != null && !binding.getPermissions().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_PERMISSIONS,
                    binding.getPermissions()));
        }

        // Scope info
        if (binding.getScope() != null) {
            b.addAttribute(AttributeBuilder.build(
                    "scope",
                    binding.getScope()));
        }

        if (binding.getScopeResourceId() != null) {
            b.addAttribute(AttributeBuilder.build(
                    "scopeResourceId",
                    binding.getScopeResourceId()));
        }

        return b.build();
    }

    private ConnectorObject toAgentConnectorObject(ObjectClass objectClass,
                                                   AzureAgentDescriptor agent) {
        if (agent == null) {
            return null;
        }

        ConnectorObjectBuilder b = new ConnectorObjectBuilder();
        b.setObjectClass(objectClass);

        String agentId = agent.getId();
        if (agentId == null || agentId.isEmpty()) {
            // Should not happen, but defensive
            return null;
        }

        String uidValue = toAgentUid(agentId);
        b.setUid(new Uid(uidValue));

        String name = agent.getName() != null ? agent.getName() : agentId;
        b.setName(new Name(name));

        // Platform and identity
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                AzureAIFoundryConstants.CONNECTOR_NAME));
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_AGENT_ID,
                agentId));

        // Version: Azure list payload exposes "latest" implicitly; use a
        // synthetic "latest" version label for now.
        b.addAttribute(AttributeBuilder.build(
                AzureAIFoundryConstants.ATTR_AGENT_VERSION,
                agent.getVersionOrDefault("latest")));

        // Descriptive fields
        if (agent.getDescription() != null && !agent.getDescription().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_DESCRIPTION,
                    agent.getDescription()));
        }

        if (agent.getModel() != null && !agent.getModel().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_FOUNDATION_MODEL,
                    agent.getModel()));
        }

        if (agent.getInstructions() != null && !agent.getInstructions().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_INSTRUCTIONS,
                    agent.getInstructions()));
        }

        // RAI policy as a guardrail reference (ID only for now)
        if (agent.getRaiPolicyName() != null && !agent.getRaiPolicyName().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_GUARDRAIL_RAI_POLICY_NAME,
                    agent.getRaiPolicyName()));
        }

        // CreatedAt as ISO-8601 string
        if (agent.getCreatedAtEpochSeconds() > 0L) {
            String createdAtIso =
                    Instant.ofEpochSecond(agent.getCreatedAtEpochSeconds()).toString();
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_CREATED_AT,
                    createdAtIso));
        }

        // temperature, topP, responseFormat, toolResourcesRaw
        if (agent.getTemperature() != null) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_TEMPERATURE,
                    agent.getTemperature()));
        }
        if (agent.getTopP() != null) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_TOP_P,
                    agent.getTopP()));
        }
        if (agent.getResponseFormat() != null && !agent.getResponseFormat().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_RESPONSE_FORMAT,
                    agent.getResponseFormat()));
        }
        if (agent.getToolResourcesRaw() != null && !agent.getToolResourcesRaw().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_TOOL_RESOURCES_RAW,
                    agent.getToolResourcesRaw()));
        }

        // Metadata as flat key/value pairs, prefixed to avoid clashes.
        if (!agent.getMetadata().isEmpty()) {
            for (Map.Entry<String, String> e : agent.getMetadata().entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (value == null) {
                    continue;
                }
                String attrName = "metadata." + key;
                b.addAttribute(AttributeBuilder.build(attrName, value));
            }
        }

        // -----------------------------------------------------------------
        // Raw tools payload from Azure (toolsRaw)
        // -----------------------------------------------------------------
        if (agent.getTools() != null && !agent.getTools().isEmpty()) {
            try {
                String toolsJson = OBJECT_MAPPER.writeValueAsString(agent.getTools());
                b.addAttribute(AttributeBuilder.build(
                        AzureAIFoundryConstants.ATTR_TOOLS_RAW,
                        toolsJson));
            } catch (JsonProcessingException e) {
                LOG.warn("Failed to serialize tools for agent {0}: {1}", agentId, e.getMessage());
            }
        }

        // ------------------------------------------------------------
        // Relationship attributes from tools-inventory.json
        // ------------------------------------------------------------

        // Knowledge bases associated with this agent
        java.util.List<String> kbIds = client.getKnowledgeBaseIdsForAgent(agentId);
        if (kbIds != null && !kbIds.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_AGENT_KNOWLEDGE_BASE_IDS,
                    kbIds));
        }

        // Guardrail associated with this agent (single)
        String guardrailId = client.getGuardrailIdForAgent(agentId);
        if (guardrailId != null && !guardrailId.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_AGENT_GUARDRAIL_ID,
                    guardrailId));
        }

        // Tools associated with this agent (multi-valued)
        java.util.List<String> toolIds = client.getToolIdsForAgent(agentId);
        if (toolIds != null && !toolIds.isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_AGENT_TOOL_IDS,
                    toolIds));
        }

        // Connected agents: agent IDs from connected_agent tools
        if (!agent.getConnectedAgentIds().isEmpty()) {
            b.addAttribute(AttributeBuilder.build(
                    AzureAIFoundryConstants.ATTR_CONNECTED_AGENTS,
                    agent.getConnectedAgentIds()));
        }

        // Entra Agent Identity enrichment (best-effort, gated by config)
        if (connection.getConfiguration().isEntraAgentIdLookupEnabled()) {
            String entraObjectId = client.getEntraAgentObjectId(agent.getName());
            if (entraObjectId != null && !entraObjectId.isEmpty()) {
                b.addAttribute(AttributeBuilder.build(
                        AzureAIFoundryConstants.ATTR_ENTRA_AGENT_OBJECT_ID,
                        entraObjectId));
            }
        }

        return b.build();
    }

    private String toAgentUid(String agentId) {
        // For Azure we currently treat the agent ID as the UID directly.
        // This helper exists so that future UID formats can be introduced
        // without changing the rest of the code.
        return agentId;
    }

    // =================================================================
    // Internal DTO used for identity bindings (future RBAC wiring)
    // =================================================================

    /**
     * Normalized identity binding, parallel to the AgentIdentityBinding
     * concept used in the Bedrock connector. This is currently unused
     * but reserved for future Azure RBAC integration.
     */
    private static final class AgentIdentityBinding {
        final String agentId;
        final String agentVersion;
        final String scope;          // e.g., "WORKSPACE", "PROJECT", "ENDPOINT"
        final String scopeResourceId;
        final String principalId;    // Entra objectId
        final String principalType;  // User, Group, ServicePrincipal, ManagedIdentity
        final String roleDefinitionId;
        final String roleName;
        final List<String> permissions;

        AgentIdentityBinding(String agentId,
                             String agentVersion,
                             String scope,
                             String scopeResourceId,
                             String principalId,
                             String principalType,
                             String roleDefinitionId,
                             String roleName,
                             List<String> permissions) {
            this.agentId = agentId;
            this.agentVersion = agentVersion;
            this.scope = scope;
            this.scopeResourceId = scopeResourceId;
            this.principalId = principalId;
            this.principalType = principalType;
            this.roleDefinitionId = roleDefinitionId;
            this.roleName = roleName;
            this.permissions = permissions != null ? permissions : new ArrayList<>();
        }
    }
}