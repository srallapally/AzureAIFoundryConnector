package org.forgerock.openicf.connectors.azureaifoundry;

import org.forgerock.openicf.connectors.azureaifoundry.client.AzureAIFoundryClient;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;

import java.io.Closeable;
import java.io.IOException;

/**
 * Manages the lifecycle of the AzureAIFoundryClient for the connector.
 *
 * This class is instantiated by the connector during init(), and closed
 * during dispose().
 */
public class AzureAIFoundryConnection implements Closeable {

    private static final Log LOG = Log.getLog(AzureAIFoundryConnection.class);

    private final AzureAIFoundryConfiguration configuration;
    private AzureAIFoundryClient client;
    private String agentServiceEndpoint;
    private String toolsInventoryUrl;
    private String toolsInventoryFilePath;
    /**
     * Create a new connection using the given configuration.
     *
     * The constructor initializes the underlying AzureAIFoundryClient using either:
     *  - managed identity / DefaultAzureCredential when useManagedIdentity == true
     *  - explicit clientId / clientSecret otherwise
     */
    public AzureAIFoundryConnection(AzureAIFoundryConfiguration configuration) {
        this.configuration = configuration;
        this.client = createClient(configuration);
        this.agentServiceEndpoint = configuration.getAgentServiceEndpoint();
        this.toolsInventoryUrl = configuration.getToolsInventoryUrl();
        this.toolsInventoryFilePath = configuration.getToolsInventoryFilePath();

    }
    private AzureAIFoundryClient createClient(AzureAIFoundryConfiguration config) {
        String tenantId = config.getTenantId();
        String subscriptionId = config.getSubscriptionId();
        String defaultLocation = config.getDefaultLocation();
        String agentServiceEndpoint = config.getAgentServiceEndpoint();
        this.toolsInventoryUrl = configuration.getToolsInventoryUrl();
        this.toolsInventoryFilePath = configuration.getToolsInventoryFilePath();

        if (config.isUseManagedIdentity()) {
            return new AzureAIFoundryClient(
                    tenantId,
                    subscriptionId,
                    defaultLocation,
                    agentServiceEndpoint,
                    toolsInventoryUrl,
                    toolsInventoryFilePath
            );
        } else {
            String clientId = config.getClientId();
            String clientSecret = toPlainString(config.getClientSecret());

            return new AzureAIFoundryClient(
                    tenantId,
                    subscriptionId,
                    defaultLocation,
                    agentServiceEndpoint,
                    clientId,
                    clientSecret,
                    toolsInventoryUrl,
                    toolsInventoryFilePath
            );
        }
    }


    private String toPlainString(GuardedString guarded) {
        if (guarded == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        guarded.access(chars -> sb.append(chars));
        return sb.toString();
    }

    /**
     * Returns the underlying AzureAIFoundryClient used for CRUDQ operations.
     */
    public AzureAIFoundryClient getClient() {
        return client;
    }

    /**
     * Expose configuration so other components (e.g., CrudService) can
     * inspect behavior flags (identityBindingScanEnabled, filters, etc.).
     */
    public AzureAIFoundryConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Simple connectivity test used by TestOp.
     *
     * For now this just attempts a light-weight listAgents() call; if it
     * throws, the caller will surface the error.
     */
    public void test() {
        LOG.ok("Testing AzureAIFoundryConnection by listing agents...");
        // Let any exceptions propagate; the connector's TestOp will catch
        // and re-wrap as needed.
        client.listAgents();
        LOG.ok("AzureAIFoundryConnection test completed successfully.");
    }

    @Override
    public void close() throws IOException {
        LOG.ok("Closing AzureAIFoundryConnection...");
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOG.warn(e, "Error while closing AzureAIFoundryClient");
            } finally {
                client = null;
            }
        }
    }
}
