package org.forgerock.openicf.connectors.azureaifoundry;

import org.forgerock.openicf.connectors.azureaifoundry.utils.AzureAIFoundryConstants;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * Configuration for the Azure AI Foundry OpenICF connector.
 *
 * Parameters are directly aligned with AzureAIFoundryClient:
 *  - tenantId
 *  - subscriptionId
 *  - optional resourceGroupName / workspaceName scoping
 *  - defaultLocation
 *  - managed identity vs explicit clientId / clientSecret
 */
public class AzureAIFoundryConfiguration extends AbstractConfiguration {

    private static final Log LOG = Log.getLog(AzureAIFoundryConfiguration.class);

    // ---------------------------------------------------------------------
    // Core Azure / subscription configuration
    // ---------------------------------------------------------------------

    /**
     * Entra ID (Azure AD) tenant ID (GUID).
     */
    private String tenantId;

    /**
     * Azure subscription ID (GUID) where Azure AI Foundry resources reside.
     */
    private String subscriptionId;

    /**
     * Optional: Resource group used to scope discovery.
     * If blank, the connector may scan multiple resource groups.
     */
    private String resourceGroupName;

    /**
     * Optional: Azure AI project/workspace name used to scope discovery.
     * If blank, the connector may scan multiple workspaces.
     */
    private String workspaceName;

    /**
     * Default Azure location (region), e.g., "eastus".
     * Used when the API requires an explicit location.
     */
    private String defaultLocation = AzureAIFoundryConstants.DEFAULT_LOCATION;

    public static final String ATTR_AGENT_API_FLAVOR = "agentApiFlavor";
    public static final String ATTR_API_VERSION = "apiVersion";
    private String agentApiFlavor; // classic | new
    private String apiVersion;
    // ---------------------------------------------------------------------
    // Authentication / identity configuration
    // ---------------------------------------------------------------------

    /**
     * When true, use managed identity / DefaultAzureCredential.
     * When false, use explicit clientId/clientSecret.
     */
    private boolean useManagedIdentity = true;

    /**
     * Client ID of the service principal to use for authentication when
     * useManagedIdentity is false.
     */
    private String clientId;

    /**
     * Client secret for the service principal used when
     * useManagedIdentity is false.
     */
    private GuardedString clientSecret;

    // Project endpoint for the Agent Service, e.g.:
    private String agentServiceEndpoint;

    // ---------------------------------------------------------------------
    // Tools inventory configuration
    // ---------------------------------------------------------------------

    /**
     * Optional URL pointing to the tools inventory JSON.
     * This can be:
     *  - A direct HTTPS URL to an Azure Blob (with SAS)
     *  - An Azure Function / App Service endpoint that returns the inventory
     */
    private String toolsInventoryUrl;

    /**
     * Optional filesystem path to a tools inventory JSON file.
     * Intended primarily for local testing (Ping IDM runtimes typically
     * will NOT use this in production).
     */
    private String toolsInventoryFilePath;

    // ---------------------------------------------------------------------
    // Behavior / discovery flags
    // ---------------------------------------------------------------------

    /**
     * When true, the connector will attempt to discover identity bindings
     * (Agent ↔ Principal) via Azure RBAC role assignments.
     */
    private boolean identityBindingScanEnabled = true;

    /**
     * Optional regex used to filter agents by name on the connector side.
     * Mirrors the Bedrock agent name filter behavior.
     */
    private String agentNameFilterRegex;

    /**
     * When true, the connector queries Microsoft Graph beta API to resolve
     * Entra agent identity service principals and correlate them to Foundry
     * agents by displayName. Requires Application.Read.All permission.
     * Default: false (opt-in to avoid requiring extra Graph permissions).
     */
    private boolean entraAgentIdLookupEnabled = false;

    // ---------------------------------------------------------------------
    // Getters / setters with @ConfigurationProperty annotations
    // ---------------------------------------------------------------------

    @ConfigurationProperty(
            order = 1,
            displayMessageKey = "tenantId.display",
            helpMessageKey = "tenantId.help",
            required = true
    )
    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    @ConfigurationProperty(
            order = 2,
            displayMessageKey = "subscriptionId.display",
            helpMessageKey = "subscriptionId.help",
            required = true
    )
    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    @ConfigurationProperty(
            order = 3,
            displayMessageKey = "resourceGroupName.display",
            helpMessageKey = "resourceGroupName.help",
            required = false
    )
    public String getResourceGroupName() {
        return resourceGroupName;
    }

    public void setResourceGroupName(String resourceGroupName) {
        this.resourceGroupName = resourceGroupName;
    }

    @ConfigurationProperty(
            order = 4,
            displayMessageKey = "workspaceName.display",
            helpMessageKey = "workspaceName.help",
            required = false
    )
    public String getWorkspaceName() {
        return workspaceName;
    }

    public void setWorkspaceName(String workspaceName) {
        this.workspaceName = workspaceName;
    }

    @ConfigurationProperty(
            order = 5,
            displayMessageKey = "defaultLocation.display",
            helpMessageKey = "defaultLocation.help",
            required = true
    )
    public String getDefaultLocation() {
        return defaultLocation;
    }

    public void setDefaultLocation(String defaultLocation) {
        this.defaultLocation = defaultLocation;
    }

    @ConfigurationProperty(
            order = 6,
            displayMessageKey = "useManagedIdentity.display",
            helpMessageKey = "useManagedIdentity.help",
            required = true
    )
    public boolean isUseManagedIdentity() {
        return useManagedIdentity;
    }

    public void setUseManagedIdentity(boolean useManagedIdentity) {
        this.useManagedIdentity = useManagedIdentity;
    }

    @ConfigurationProperty(
            order = 7,
            displayMessageKey = "clientId.display",
            helpMessageKey = "clientId.help",
            required = false
    )
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    @ConfigurationProperty(
            order = 8,
            displayMessageKey = "clientSecret.display",
            helpMessageKey = "clientSecret.help",
            required = false,
            confidential = true
    )
    public GuardedString getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(GuardedString clientSecret) {
        this.clientSecret = clientSecret;
    }

    @ConfigurationProperty(
            order = 9,
            displayMessageKey = "identityBindingScanEnabled.display",
            helpMessageKey = "identityBindingScanEnabled.help",
            required = false
    )
    public boolean isIdentityBindingScanEnabled() {
        return identityBindingScanEnabled;
    }

    public void setIdentityBindingScanEnabled(boolean identityBindingScanEnabled) {
        this.identityBindingScanEnabled = identityBindingScanEnabled;
    }

    @ConfigurationProperty(
            order = 10,
            displayMessageKey = "agentNameFilterRegex.display",
            helpMessageKey = "agentNameFilterRegex.help",
            required = false
    )
    public String getAgentNameFilterRegex() {
        return agentNameFilterRegex;
    }

    public void setAgentNameFilterRegex(String agentNameFilterRegex) {
        this.agentNameFilterRegex = agentNameFilterRegex;
    }

    @ConfigurationProperty(
            order = 11,
            displayMessageKey = "agentServiceEndpoint.display",
            helpMessageKey = "agentServiceEndpoint.help",
            required = true
    )
    public String getAgentServiceEndpoint() {
        return agentServiceEndpoint;
    }

    public void setAgentServiceEndpoint(String agentServiceEndpoint) {
        this.agentServiceEndpoint = agentServiceEndpoint;
    }

    @ConfigurationProperty(
            order = 12,
            displayMessageKey = "toolsInventoryUrl.display",
            helpMessageKey = "toolsInventoryUrl.help",
            required = false
    )
    public String getToolsInventoryUrl() {
        return toolsInventoryUrl;
    }

    public void setToolsInventoryUrl(String toolsInventoryUrl) {
        this.toolsInventoryUrl = toolsInventoryUrl;
    }

    @ConfigurationProperty(
            order = 13,
            displayMessageKey = "toolsInventoryFilePath.display",
            helpMessageKey = "toolsInventoryFilePath.help",
            required = false
    )
    public String getToolsInventoryFilePath() {
        return toolsInventoryFilePath;
    }

    public void setToolsInventoryFilePath(String toolsInventoryFilePath) {
        this.toolsInventoryFilePath = toolsInventoryFilePath;
    }

    @ConfigurationProperty(
            order = 14,
            displayMessageKey = "agentApiFlavor.display",
            helpMessageKey = "agentApiFlavor.help",
            required = false
    )
    public String getAgentApiFlavor() {
        return agentApiFlavor != null ? agentApiFlavor.toLowerCase() : "classic";
    }

    public void setAgentApiFlavor(String agentApiFlavor) {
        this.agentApiFlavor = agentApiFlavor;
    }

    @ConfigurationProperty(
            order = 15,
            displayMessageKey = "agentApiVersion.display",
            helpMessageKey = "agentApiVersion.help",
            required = false
    )
    public String getApiVersion() {
        return apiVersion != null ? apiVersion : "v1";
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    @ConfigurationProperty(
            order = 16,
            displayMessageKey = "entraAgentIdLookupEnabled.display",
            helpMessageKey = "entraAgentIdLookupEnabled.help",
            required = false
    )
    public boolean isEntraAgentIdLookupEnabled() {
        return entraAgentIdLookupEnabled;
    }

    public void setEntraAgentIdLookupEnabled(boolean entraAgentIdLookupEnabled) {
        this.entraAgentIdLookupEnabled = entraAgentIdLookupEnabled;
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    @Override
    public void validate() {
        LOG.ok("Validating AzureAIFoundryConfiguration...");

        if (StringUtil.isBlank(tenantId)) {
            throw new IllegalArgumentException(
                    "tenantId must be specified for Azure AI Foundry connector.");
        }
        if (StringUtil.isBlank(subscriptionId)) {
            throw new IllegalArgumentException(
                    "subscriptionId must be specified for Azure AI Foundry connector.");
        }
        if (StringUtil.isBlank(agentServiceEndpoint)) {
            throw new IllegalArgumentException(
                    "agentServiceEndpoint must be specified for Azure AI Foundry connector.");
        }
        if (StringUtil.isBlank(defaultLocation)) {
            throw new IllegalArgumentException(
                    "defaultLocation must be specified for Azure AI Foundry connector.");
        }

        if (!useManagedIdentity) {
            if (StringUtil.isBlank(clientId)) {
                throw new IllegalArgumentException(
                        "clientId must be specified when not using managed identity.");
            }
            if (clientSecret == null) {
                throw new IllegalArgumentException(
                        "clientSecret must be specified when not using managed identity.");
            }
        }

        if (StringUtil.isBlank(toolsInventoryUrl) &&
                StringUtil.isBlank(toolsInventoryFilePath)) {
            LOG.ok("No toolsInventoryUrl/toolsInventoryFilePath configured. " +
                    "agentTool objectClass and Agent.tools will be empty.");
        }
        // Optional: log scoping for troubleshooting (but not secrets)
        LOG.ok("AzureAIFoundryConfiguration validated. tenantId={0}, subscriptionId={1}, " +
                        "resourceGroupName={2}, workspaceName={3}, defaultLocation={4}, useManagedIdentity={5}, " +
                        "identityBindingScanEnabled={6}",
                tenantId,
                subscriptionId,
                resourceGroupName,
                workspaceName,
                defaultLocation,
                useManagedIdentity,
                identityBindingScanEnabled);
    }
}
