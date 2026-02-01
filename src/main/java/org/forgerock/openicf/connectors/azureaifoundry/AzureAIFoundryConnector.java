package org.forgerock.openicf.connectors.azureaifoundry;

import org.forgerock.openicf.connectors.azureaifoundry.operations.AzureAIFoundryCrudService;
import org.forgerock.openicf.connectors.azureaifoundry.utils.AzureAIFoundryConstants;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterTranslator;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.Connector;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.SearchResultsHandler;
import org.identityconnectors.framework.spi.operations.SchemaOp;
import org.identityconnectors.framework.spi.operations.SearchOp;
import org.identityconnectors.framework.spi.operations.TestOp;

/**
 * OpenICF connector for Azure AI Foundry Agents.
 *
 * This connector is read-only for v1 and focuses on discovery of agents.
 * It mirrors the structure of the Bedrock connector:
 *  - AzureAIFoundryConfiguration (config)
 *  - AzureAIFoundryConnection   (connection + client lifecycle)
 *  - AzureAIFoundryCrudService  (CRUDQ logic)
 */
@ConnectorClass(
        configurationClass = AzureAIFoundryConfiguration.class,
        displayNameKey = "azureAIFoundry.connector.display"
)
public class AzureAIFoundryConnector implements
        Connector,
        SearchOp<Filter>,
        SchemaOp,
        TestOp {

    private static final Log LOG = Log.getLog(AzureAIFoundryConnector.class);

    private AzureAIFoundryConfiguration configuration;
    private AzureAIFoundryConnection connection;
    private AzureAIFoundryCrudService crudService;

    // ---------------------------------------------------------------------
    // Connector lifecycle
    // ---------------------------------------------------------------------

    @Override
    public void init(Configuration configuration) {
        LOG.ok("Initializing AzureAIFoundryConnector...");

        if (!(configuration instanceof AzureAIFoundryConfiguration)) {
            throw new IllegalArgumentException(
                    "Configuration must be an instance of AzureAIFoundryConfiguration");
        }

        this.configuration = (AzureAIFoundryConfiguration) configuration;
        this.configuration.validate();

        this.connection = new AzureAIFoundryConnection(this.configuration);
        this.crudService = new AzureAIFoundryCrudService(this.connection);

        LOG.ok("AzureAIFoundryConnector initialized.");
    }

    @Override
    public void dispose() {
        LOG.ok("Disposing AzureAIFoundryConnector...");
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing AzureAIFoundryConnection.");
            } finally {
                connection = null;
            }
        }
        crudService = null;
        configuration = null;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    // ---------------------------------------------------------------------
    // TestOp
    // ---------------------------------------------------------------------

    @Override
    public void test() {
        LOG.ok("Executing TestOp on AzureAIFoundryConnector...");
        if (connection == null) {
            throw new IllegalStateException("Connection is not initialized.");
        }
        connection.test();
    }

    // ---------------------------------------------------------------------
    // SchemaOp
    // ---------------------------------------------------------------------

    @Override
    public Schema schema() {
        LOG.ok("Building schema for AzureAIFoundryConnector...");

        SchemaBuilder builder = new SchemaBuilder(AzureAIFoundryConnector.class);

        // -----------------------------------------------------------------
        // Agent object class (core AgentInventory equivalent)
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder agentOc = new ObjectClassInfoBuilder();
        agentOc.setType(AzureAIFoundryConstants.OC_AGENT);

        // UID / NAME are implicit: Uid + Name
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_AGENT_ID,
                String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_AGENT_VERSION,
                String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_DESCRIPTION,
                String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_FOUNDATION_MODEL,
                String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_CREATED_AT,
                String.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_GUARDRAIL_RAI_POLICY_NAME,
                String.class));
        // Normalized list of tool IDs (aligned with Bedrock)
        agentOc.addAttributeInfo(
                AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_TOOLS)
                        .setType(String.class)
                        .setMultiValued(true)
                        .build());
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                        AzureAIFoundryConstants.ATTR_GUARDRAIL_ID)
                .setType(String.class)
                .build());
        // Raw Azure tools payload as JSON (single large string)
        agentOc.addAttributeInfo(
                AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_TOOLS_RAW)
                        .setType(String.class)
                        .build());

        // Relationship: Agent → Tools
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                AzureAIFoundryConstants.ATTR_AGENT_TOOL_IDS)
                .setType(String.class)
                .setMultiValued(true)
                .build());

        // Relationship: Agent → KnowledgeBases
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                AzureAIFoundryConstants.ATTR_AGENT_KNOWLEDGE_BASE_IDS)
                .setType(String.class)
                .setMultiValued(true)
                .build());

        // Relationship: Agent → Guardrail (single, but we could mark multi-valued if you prefer)
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                AzureAIFoundryConstants.ATTR_AGENT_GUARDRAIL_ID)
                .setType(String.class)
                .build());

        builder.defineObjectClass(agentOc.build());

       /* -----------------------------------------------------------------
          // Tool object class (agentTool) – describes an API capability /
          // action group that an agent can invoke.
          // -----------------------------------------------------------------*/
        ObjectClassInfoBuilder toolOc = new ObjectClassInfoBuilder();
        toolOc.setType(AzureAIFoundryConstants.OC_TOOL);

        // UID / NAME are implicit: Uid + Name
        // We expose core tool/action-group properties that we know today.
        // Owning agent
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_AGENT_ID,
                String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_ACTION_GROUP_ID,
                String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_ACTION_GROUP_NAME,
                String.class));
        // Description
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_DESCRIPTION,
                String.class));

        // Type (no constant; schema-level attr name is "toolType")
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                "toolType",
                String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_ACTION_GROUP_EXECUTOR_ARN,
                String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_ACTION_GROUP_PARENT_SIGNATURE,
                String.class));
        toolOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_ACTION_GROUP_SCHEMA_URI,
                String.class));
        builder.defineObjectClass(toolOc.build());

        // -----------------------------------------------------------------
        // Knowledge base object class (agentKnowledgeBase)
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder kbOc = new ObjectClassInfoBuilder();
        kbOc.setType(AzureAIFoundryConstants.OC_KNOWLEDGE_BASE);

        // UID / NAME are implicit
        // Platform
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                String.class));

        // Knowledge base id and state
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_KNOWLEDGE_BASE_ID,
                String.class));
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_KNOWLEDGE_BASE_STATE,
                String.class));

        // Optional additional attributes from the descriptor
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                "sourceType",
                String.class));
        kbOc.addAttributeInfo(AttributeInfoBuilder.build(
                "connectionRef",
                String.class));

        builder.defineObjectClass(kbOc.build());
        // -----------------------------------------------------------------
        // Guardrail object class (agentGuardrail)
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder guardrailOc = new ObjectClassInfoBuilder();
        guardrailOc.setType(AzureAIFoundryConstants.OC_GUARDRAIL);

        // Platform
        guardrailOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_PLATFORM,
                String.class));

        // Guardrail ID (internal ID, same as __UID__)
        guardrailOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_GUARDRAIL_ID,
                String.class));

        // Full rai_policy_name, e.g. /subscriptions/.../raiPolicies/MyGuardrail123
        guardrailOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_GUARDRAIL_RAI_POLICY_NAME,
                String.class));

        // Short name extracted from rai_policy_name (e.g., MyGuardrail123)
        guardrailOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_GUARDRAIL_RAI_POLICY_SHORT_NAME,
                String.class));

        // Relationship preview: which agents reference this guardrail
        guardrailOc.addAttributeInfo(
                AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_GUARDRAIL_AGENT_IDS)
                        .setType(String.class)
                        .setMultiValued(true)
                        .build());
        builder.defineObjectClass(guardrailOc.build());

        Schema schema = builder.build();
        LOG.ok("Schema built for AzureAIFoundryConnector.");
        return schema;
    }

    // ---------------------------------------------------------------------
    // SearchOp<Filter>
    // ---------------------------------------------------------------------

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass, OperationOptions operationOptions) {
        return null;
    }

    @Override
    public void executeQuery(ObjectClass objectClass,
                             Filter filter,
                             ResultsHandler handler,
                             OperationOptions options) {

        if (crudService == null) {
            throw new IllegalStateException("CRUD service is not initialized.");
        }

        String ocName = objectClass.getObjectClassValue();

        if (ObjectClass.ALL.equals(objectClass) ||
                AzureAIFoundryConstants.OC_AGENT.equals(ocName)) {
            crudService.searchAgents(objectClass, filter, handler, options);
        } else if (AzureAIFoundryConstants.OC_GUARDRAIL.equals(ocName)) {
            crudService.searchGuardrails(objectClass, filter, handler, options);
        } else if (AzureAIFoundryConstants.OC_KNOWLEDGE_BASE.equals(ocName)) {
            crudService.searchKnowledgeBases(objectClass, filter, handler, options);
        } else if (AzureAIFoundryConstants.OC_TOOL.equals(ocName)) {
            crudService.searchTools(objectClass, filter, handler, options);
        } else if (AzureAIFoundryConstants.OC_IDENTITY_BINDING.equals(ocName)) {
            crudService.searchIdentityBindings(objectClass, filter, handler, options);
        } else {
            LOG.warn("Unsupported objectClass for executeQuery: {0}", ocName);
        }
    }
}
