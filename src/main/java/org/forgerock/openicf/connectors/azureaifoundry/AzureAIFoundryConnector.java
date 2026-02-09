package org.forgerock.openicf.connectors.azureaifoundry;

import org.forgerock.openicf.connectors.azureaifoundry.operations.AzureAIFoundryCrudService;
import org.forgerock.openicf.connectors.azureaifoundry.utils.AzureAIFoundryConstants;
import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
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
        displayNameKey = "azureaifoundry.connector.display"
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
        agentOc.setType(ObjectClass.ACCOUNT_NAME);

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
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_TEMPERATURE,
                Double.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_TOP_P,
                Double.class));
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_RESPONSE_FORMAT,
                String.class));
        // Normalized list of tool IDs (aligned with Bedrock)
        agentOc.addAttributeInfo(
                AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_TOOLS)
                        .setType(String.class)
                        .setMultiValued(true)
                        .build());
        // Raw Azure tools payload as JSON (single large string)
        agentOc.addAttributeInfo(
                AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_TOOLS_RAW)
                        .setType(String.class)
                        .build());
        agentOc.addAttributeInfo(AttributeInfoBuilder.build(
                AzureAIFoundryConstants.ATTR_TOOL_RESOURCES_RAW,
                String.class));
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

        // Relationship: Agent → Connected Agents (via connected_agent tools)
        agentOc.addAttributeInfo(AttributeInfoBuilder.define(
                        AzureAIFoundryConstants.ATTR_CONNECTED_AGENTS)
                .setType(String.class)
                .setMultiValued(true)
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

        // -----------------------------------------------------------------
        // Identity binding object class (agentIdentityBinding)
        // -----------------------------------------------------------------
        ObjectClassInfoBuilder ibOc = new ObjectClassInfoBuilder();
        ibOc.setType(AzureAIFoundryConstants.OC_IDENTITY_BINDING);

        // Platform
        ibOc.addAttributeInfo(AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_PLATFORM)
                .setType(String.class)
                .setMultiValued(false)
                .build());

        // Agent reference
        ibOc.addAttributeInfo(AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_AGENT_ID)
                .setType(String.class)
                .setMultiValued(false)
                .build());

        ibOc.addAttributeInfo(AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_AGENT_VERSION)
                .setType(String.class)
                .setMultiValued(false)
                .build());

        // Binding properties
        ibOc.addAttributeInfo(AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_KIND)
                .setType(String.class)
                .setMultiValued(false)
                .build());

        ibOc.addAttributeInfo(AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_PRINCIPAL)
                .setType(String.class)
                .setMultiValued(false)
                .build());

        // Permissions (multi-valued)
        ibOc.addAttributeInfo(AttributeInfoBuilder.define(AzureAIFoundryConstants.ATTR_PERMISSIONS)
                        .setType(String.class)
                        .setMultiValued(true)
                        .build());

        // Scope attributes
        ibOc.addAttributeInfo(AttributeInfoBuilder.define("scope")
                        .setType(String.class)
                        .setMultiValued(false)
                        .build());
        ibOc.addAttributeInfo(AttributeInfoBuilder.define("scopeResourceId")
                        .setType(String.class)
                        .setMultiValued(false)
                        .build());

        builder.defineObjectClass(ibOc.build());
        Schema schema = builder.build();
        LOG.ok("Schema built for AzureAIFoundryConnector.");
        return schema;
    }

    // ---------------------------------------------------------------------
    // SearchOp<Filter>
    // ---------------------------------------------------------------------

    @Override
    public FilterTranslator<Filter> createFilterTranslator(ObjectClass objectClass, OperationOptions operationOptions) {
        return CollectionUtil::newList;
    }

    @Override
    public void executeQuery(ObjectClass objectClass,
                             Filter filter,
                             ResultsHandler handler,
                             OperationOptions options) {

        if (crudService == null) {
            throw new IllegalStateException("CRUD service is not initialized.");
        }

        if (options != null && options.getPageSize() != null && options.getPageSize() < 0) {
            throw new InvalidAttributeValueException("Page size should not be less than zero.");
        }

        LOG.ok("executeQuery called for objectClass {0}, filter {1}", objectClass, filter);

        // Detect GET vs QUERY based on UID filter
        Uid uid = getUidIfGetOperation(filter);

        // GET-by-UID: no paging, just a single object
        if (uid != null) {
            handleGetByUid(objectClass, uid, handler, options);
            return;
        }

        // QUERY: apply pageSize and cookie semantics
        int pageSize = (options != null && options.getPageSize() != null)
                ? options.getPageSize()
                : -1;

        int offset = 0;
        if (options != null && options.getPagedResultsCookie() != null) {
            try {
                offset = Integer.parseInt(options.getPagedResultsCookie());
            } catch (NumberFormatException e) {
                LOG.warn(e, "Invalid pagedResultsCookie value: {0}", options.getPagedResultsCookie());
            }
        }

        PagingResultsHandler pagingHandler = new PagingResultsHandler(handler, offset, pageSize);

        String ocName = objectClass.getObjectClassValue();

        if (ObjectClass.ALL.equals(objectClass) ||
                AzureAIFoundryConstants.OC_AGENT.equals(ocName)) {
            crudService.searchAgents(objectClass, filter, pagingHandler, options);
        } else if (AzureAIFoundryConstants.OC_GUARDRAIL.equals(ocName)) {
            crudService.searchGuardrails(objectClass, filter, pagingHandler, options);
        } else if (AzureAIFoundryConstants.OC_KNOWLEDGE_BASE.equals(ocName)) {
            crudService.searchKnowledgeBases(objectClass, filter, pagingHandler, options);
        } else if (AzureAIFoundryConstants.OC_TOOL.equals(ocName)) {
            crudService.searchTools(objectClass, filter, pagingHandler, options);
        } else if (AzureAIFoundryConstants.OC_IDENTITY_BINDING.equals(ocName)) {
            crudService.searchIdentityBindings(objectClass, filter, pagingHandler, options);
        } else {
            LOG.warn("Unsupported objectClass for executeQuery: {0}", ocName);
        }

        emitSearchResult(handler, pagingHandler, offset);
    }

    // ---------------------------------------------------------------------
    // GET-by-UID helper
    // ---------------------------------------------------------------------

    private void handleGetByUid(ObjectClass objectClass,
                                Uid uid,
                                ResultsHandler handler,
                                OperationOptions options) {

        ConnectorObject co = null;
        String ocName = objectClass.getObjectClassValue();

        if (AzureAIFoundryConstants.OC_AGENT.equals(ocName)) {
            co = crudService.getAgent(objectClass, uid, options);
        } else if (AzureAIFoundryConstants.OC_GUARDRAIL.equals(ocName)) {
            co = crudService.getGuardrail(objectClass, uid, options);
        } else if (AzureAIFoundryConstants.OC_KNOWLEDGE_BASE.equals(ocName)) {
            co = crudService.getKnowledgeBase(objectClass, uid, options);
        } else if (AzureAIFoundryConstants.OC_TOOL.equals(ocName)) {
            co = crudService.getTool(objectClass, uid, options);
        } else {
            throw new UnsupportedOperationException("Unsupported ObjectClass for GET: " + objectClass);
        }

        if (co != null) {
            handler.handle(co);
        }

        // For GET, emit a SearchResult with no cookie / remaining info
        if (handler instanceof SearchResultsHandler) {
            ((SearchResultsHandler) handler).handleResult(new SearchResult(null, -1));
        }
    }

    // ---------------------------------------------------------------------
    // Helper: detect GET-by-UID pattern
    // ---------------------------------------------------------------------

    private Uid getUidIfGetOperation(Filter query) {
        if (query instanceof EqualsFilter) {
            Attribute attr = ((EqualsFilter) query).getAttribute();
            if (attr != null && Uid.NAME.equals(attr.getName()) && !attr.getValue().isEmpty()) {
                Object value = attr.getValue().get(0);
                if (value instanceof String) {
                    return new Uid((String) value);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Paging helpers
    // ---------------------------------------------------------------------

    /**
     * Wrapper handler that applies offset + pageSize semantics on top of the
     * underlying ResultsHandler while still letting the CRUD service iterate
     * over all matches (so we know totalCount).
     */
    private static final class PagingResultsHandler implements ResultsHandler {

        private final ResultsHandler delegate;
        private final int offset;
        private final int pageSize;  // -1 or 0 means "no limit"

        private int seen = 0;
        private int returned = 0;

        PagingResultsHandler(ResultsHandler delegate, int offset, int pageSize) {
            this.delegate = delegate;
            this.offset = Math.max(0, offset);
            this.pageSize = pageSize;
        }

        @Override
        public boolean handle(ConnectorObject obj) {
            seen++;

            // Skip until we reach the offset
            if (seen <= offset) {
                return true;
            }

            // If we have a pageSize limit and already returned that many, skip
            if (pageSize > 0 && returned >= pageSize) {
                // We still return true to let CRUD iterate all and update seen,
                // but we don't forward to the delegate anymore.
                return true;
            }

            boolean cont = delegate.handle(obj);
            if (cont) {
                returned++;
            }
            return cont;
        }

        int getSeen() {
            return seen;
        }

        int getReturned() {
            return returned;
        }
    }

    /**
     * Emits a SearchResult with cookie and remaining based on what the
     * PagingResultsHandler observed.
     */
    private void emitSearchResult(ResultsHandler handler,
                                  PagingResultsHandler pagingHandler,
                                  int offset) {
        if (!(handler instanceof SearchResultsHandler)) {
            return;
        }

        int totalCount = pagingHandler.getSeen();
        int returnedCount = pagingHandler.getReturned();

        String cookie = null;
        if (returnedCount > 0 && totalCount > offset + returnedCount) {
            cookie = String.valueOf(offset + returnedCount);
        }

        int remaining = (totalCount < 0 || returnedCount < 0)
                ? -1
                : Math.max(0, totalCount - (offset + returnedCount));

        ((SearchResultsHandler) handler).handleResult(new SearchResult(cookie, remaining));
    }
}