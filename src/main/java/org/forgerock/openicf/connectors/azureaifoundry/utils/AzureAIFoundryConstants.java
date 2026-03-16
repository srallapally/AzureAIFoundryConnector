package org.forgerock.openicf.connectors.azureaifoundry.utils;

/**
 * Shared constants for the Azure AI Foundry connector.
 */
public abstract class AzureAIFoundryConstants {

    // ---------------------------------------------------------------------
    // Connector identification
    // ---------------------------------------------------------------------
    public static final String CONNECTOR_NAME = "azureaifoundry";

    // Default Azure location if none is explicitly configured
    public static final String DEFAULT_LOCATION = "eastus";

    // UID separator for composite identifiers (agentId:guardrailId:version, etc.)
    public static final String UID_SEPARATOR = ":";

    public static final String AGENT_API_FLAVOR_CLASSIC = "classic";
    public static final String AGENT_API_FLAVOR_NEW = "new";

    public static final String AGENTS_BASE_PATH_CLASSIC = "/agents";
    public static final String AGENTS_BASE_PATH_NEW = "/assistants";

    // ---------------------------------------------------------------------
    // Object class names (aligned with AwsBedrockConstants)
    // ---------------------------------------------------------------------
    public static final String OC_AGENT = "agent";
    public static final String OC_GUARDRAIL = "agentGuardrail";
    public static final String OC_TOOL = "agentTool";
    public static final String OC_IDENTITY_BINDING = "agentIdentityBinding";
    public static final String OC_KNOWLEDGE_BASE = "agentKnowledgeBase";

    // ---------------------------------------------------------------------
    // Common attribute names
    // ---------------------------------------------------------------------
    public static final String ATTR_PLATFORM = "platform";           // e.g., "AZURE_AI_FOUNDRY"
    public static final String ATTR_AGENT_ID = "agentId";
    public static final String ATTR_AGENT_VERSION = "agentVersion";

    // ---------------------------------------------------------------------
    // Agent attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_VERSION = "version";
    public static final String ATTR_STATUS = "status";
    public static final String ATTR_DESCRIPTION = "description";
    public static final String ATTR_FOUNDATION_MODEL = "foundationModel"; // model / deployment
    public static final String ATTR_ROLE_ARN = "roleArn";                 // for Azure: "invocation identity" resourceId
    public static final String ATTR_IDLE_TTL = "idleSessionTtlSeconds";
    public static final String ATTR_CREATED_AT = "createdAt";
    public static final String ATTR_UPDATED_AT = "updatedAt";
    public static final String ATTR_TEMPERATURE = "temperature";
    public static final String ATTR_TOP_P = "topP";
    public static final String ATTR_RESPONSE_FORMAT = "responseFormat";
    public static final String ATTR_INSTRUCTIONS = "instructions";
    public static final String ATTR_TOOL_RESOURCES_RAW = "toolResourcesRaw";

    // Relationship attributes on the Agent
    public static final String ATTR_TOOLS = "tools";                     // list of tool IDs
    public static final String ATTR_KNOWLEDGE_BASES = "knowledgeBases";  // list of KB IDs
    public static final String ATTR_GUARDRAIL_ID = "guardrailId";
    public static final String ATTR_GUARDRAIL_VERSION = "guardrailVersion";

    // Raw Azure tools payload, serialized as JSON (per-agent)
    public static final String ATTR_TOOLS_RAW = "toolsRaw";
    public static final String ATTR_AGENT_TOOL_IDS = "toolIds";

    // ---------------------------------------------------------------------
    // Knowledge base attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KNOWLEDGE_BASE_ID = "knowledgeBaseId";
    public static final String ATTR_KNOWLEDGE_BASE_STATE = "knowledgeBaseState";

    // ---------------------------------------------------------------------
    // Tool (Action Group / API capability) attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_ACTION_GROUP_ID = "actionGroupId";
    public static final String ATTR_ACTION_GROUP_NAME = "actionGroupName";
    public static final String ATTR_ACTION_GROUP_EXECUTOR_ARN = "executorArn"; // Azure function / endpoint resourceId
    public static final String ATTR_ACTION_GROUP_PARENT_SIGNATURE = "parentActionGroupSignature";
    public static final String ATTR_ACTION_GROUP_SCHEMA_URI = "schemaUri";

    // ---------------------------------------------------------------------
    // Identity binding attributes
    // ---------------------------------------------------------------------
    public static final String ATTR_KIND = "kind";           // DIRECT, GROUP, MANAGED_IDENTITY, etc.
    public static final String ATTR_PRINCIPAL = "principal"; // packed principal descriptor
    public static final String ATTR_PERMISSIONS = "permissions";

    // Virtual, computed principals on the agent (summary of bindings)
    public static final String ATTR_AGENT_PRINCIPALS = "agentPrincipals";
    //

    // Guardrail object class + attributes
    /** Full rai_policy_name (e.g., /subscriptions/.../raiPolicies/Guardrails608) */
    public static final String ATTR_GUARDRAIL_RAI_POLICY_NAME = "raiPolicyName";

    /** Shorter policy identifier extracted from rai_policy_name (e.g., Guardrails608) */
    public static final String ATTR_GUARDRAIL_RAI_POLICY_SHORT_NAME = "raiPolicyShortName";

    /**
     * Multi-valued list of agent IDs that reference this guardrail.
     * Populated from inventory.json → guardrails[*].agents[]
     */
    public static final String ATTR_GUARDRAIL_AGENT_IDS = "agentIdsUsingGuardrail";
    //
    // Agent relationship attributes
    //
    /** Multi-valued list of knowledge base IDs associated with the agent. */
    public static final String ATTR_AGENT_KNOWLEDGE_BASE_IDS = "knowledgeBaseIds";

    /**
     * Single guardrail ID associated with the agent.
     * Azure AI Foundry currently supports at most one guardrail per agent.
     */
    public static final String ATTR_AGENT_GUARDRAIL_ID = "agentGuardrailId";

    /**
     * Multi-valued list of agent IDs connected to this agent via connected_agent tools.
     * Extracted from tools[] where type == "connected_agent".
     */
    public static final String ATTR_CONNECTED_AGENTS = "connectedAgents";

    /**
     * Entra ID object ID of the agent identity service principal.
     * Populated via Microsoft Graph beta API when entraAgentIdLookupEnabled is true.
     * Correlation is best-effort via displayName matching.
     */
    public static final String ATTR_ENTRA_AGENT_OBJECT_ID = "entraAgentObjectId";

    private AzureAIFoundryConstants() {
        // prevent instantiation
    }
}