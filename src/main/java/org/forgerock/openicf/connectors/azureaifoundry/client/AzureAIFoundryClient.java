// src/main/java/org/forgerock/openicf/connectors/azureaifoundry/client/AzureAIFoundryClient.java
package org.forgerock.openicf.connectors.azureaifoundry.client;

import org.forgerock.openicf.connectors.azureaifoundry.utils.TokenResponse;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Azure AI Foundry client used by the OpenICF connector.
 */
public class AzureAIFoundryClient implements AutoCloseable, Closeable {

    // ---------------------------------------------------------------------
    // Core context
    // ---------------------------------------------------------------------

    private final String tenantId;
    private final String subscriptionId;
    private final String defaultLocation;
    private final String agentServiceEndpoint;
    // Tools inventory configuration (URL or file)
    private final String toolsInventoryUrl;
    private final String toolsInventoryFilePath;

    // Cached tools inventory - map by tool ID
    private Map<String, AzureToolDescriptor> cachedToolsById;
    // Cached knowledge base inventory
    private List<AzureKnowledgeBaseDescriptor> cachedKnowledgeBases;
    // Cached guardrail inventory
    private java.util.List<AzureGuardrailDescriptor> cachedGuardrails;
    // Cached identity bindings inventory
    private java.util.List<AzureIdentityBindingDescriptor> cachedIdentityBindings;
    // Agent → relationship info (tools, KBs, guardrails) from tools inventory
    private java.util.Map<String, AzureAgentRelations> cachedAgentRelations;

    private final boolean useManagedIdentity;
    private final String clientId;
    private final String clientSecret;

    private final Duration requestTimeout;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Simple token cache
    private volatile String cachedAccessToken;
    private volatile Instant cachedTokenExpiresAt; // UTC

    private static final String TOKEN_SCOPE = "https://ai.azure.com/.default";
    private static final String GRAPH_TOKEN_SCOPE = "https://graph.microsoft.com/.default";
    private static final String API_VERSION = "v1";

    // Graph API token cache (separate scope/audience from AI Foundry token)
    private volatile String cachedGraphToken;
    private volatile Instant cachedGraphTokenExpiresAt;

    // Agent identity map: displayName -> list of Entra object IDs (null = not loaded yet)
    private volatile java.util.Map<String, java.util.List<String>> agentIdentityByDisplayName;

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /**
     * Constructor for managed identity – currently NOT implemented for real use.
     * We keep it for future extension but listAgents() will fail if called
     * while useManagedIdentity is true.
     */
    public AzureAIFoundryClient(String tenantId,
                                String subscriptionId,
                                String defaultLocation,
                                String agentServiceEndpoint,
                                String toolsInventoryUrl,
                                String toolsInventoryFilePath) {
        this.tenantId = tenantId;
        this.subscriptionId = subscriptionId;
        this.defaultLocation = defaultLocation;
        this.agentServiceEndpoint = stripTrailingSlash(agentServiceEndpoint);
        this.useManagedIdentity = true;
        this.clientId = null;
        this.clientSecret = null;
        this.toolsInventoryUrl = toolsInventoryUrl;
        this.toolsInventoryFilePath = toolsInventoryFilePath;
        this.requestTimeout = Duration.ofSeconds(30);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Constructor for client credentials (recommended / required for now).
     */
    public AzureAIFoundryClient(String tenantId,
                                String subscriptionId,
                                String defaultLocation,
                                String agentServiceEndpoint,
                                String clientId,
                                String clientSecret,
                                String toolsInventoryUrl,
                                String toolsInventoryFilePath) {
        this.tenantId = tenantId;
        this.subscriptionId = subscriptionId;
        this.defaultLocation = defaultLocation;
        this.agentServiceEndpoint = stripTrailingSlash(agentServiceEndpoint);
        this.useManagedIdentity = false;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.toolsInventoryUrl = toolsInventoryUrl;
        this.toolsInventoryFilePath = toolsInventoryFilePath;
        this.requestTimeout = Duration.ofSeconds(30);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .build();

        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private String stripTrailingSlash(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    public String getTenantId() {
        return tenantId;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getDefaultLocation() {
        return defaultLocation;
    }

    public String getAgentServiceEndpoint() {
        return agentServiceEndpoint;
    }

    public boolean isUseManagedIdentity() {
        return useManagedIdentity;
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * WARNING: never log this value.
     */
    public String getClientSecret() {
        return clientSecret;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    // ---------------------------------------------------------------------
    // Token acquisition (client credentials)
    // ---------------------------------------------------------------------

    /**
     * Acquire a bearer token for the Agent Service using client credentials.
     * <p>
     * Tokens are cached until shortly before expiry (60s safety margin).
     */
    protected synchronized String acquireBearerToken() {
        if (useManagedIdentity) {
            throw new IllegalStateException(
                    "Managed identity auth is not implemented. " +
                            "Configure clientId/clientSecret for AzureAIFoundryClient.");
        }

        Instant now = Instant.now();
        if (cachedAccessToken != null && cachedTokenExpiresAt != null) {
            // Refresh 60 seconds before expiry
            if (cachedTokenExpiresAt.isAfter(now.plusSeconds(60))) {
                return cachedAccessToken;
            }
        }

        if (clientId == null || clientSecret == null) {
            throw new IllegalStateException(
                    "Client credentials (clientId/clientSecret) must be configured.");
        }

        try {
            String tokenEndpoint = "https://login.microsoftonline.com/"
                    + URLEncoder.encode(tenantId, StandardCharsets.UTF_8)
                    + "/oauth2/v2.0/token";

            String body = "client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&grant_type=client_credentials"
                    + "&scope=" + urlEncode(TOKEN_SCOPE);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("Token request failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            TokenResponse tokenResponse =
                    objectMapper.readValue(response.body(), TokenResponse.class);

            if (tokenResponse.access_token == null || tokenResponse.access_token.isEmpty()) {
                throw new RuntimeException("Token response did not contain access_token.");
            }

            long expiresIn = tokenResponse.expires_in != null ? tokenResponse.expires_in : 3600L;
            cachedAccessToken = tokenResponse.access_token;
            cachedTokenExpiresAt = now.plusSeconds(expiresIn);

            return cachedAccessToken;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error acquiring access token", e);
        }
    }

    private String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------
    // Microsoft Graph token acquisition
    // ---------------------------------------------------------------------

    /**
     * Acquire a bearer token for Microsoft Graph using client credentials.
     * Separate cache from the AI Foundry token since the scope differs.
     */
    protected synchronized String acquireGraphBearerToken() {
        if (useManagedIdentity) {
            throw new IllegalStateException(
                    "Managed identity auth is not implemented for Graph token.");
        }

        Instant now = Instant.now();
        if (cachedGraphToken != null && cachedGraphTokenExpiresAt != null) {
            if (cachedGraphTokenExpiresAt.isAfter(now.plusSeconds(60))) {
                return cachedGraphToken;
            }
        }

        if (clientId == null || clientSecret == null) {
            throw new IllegalStateException(
                    "Client credentials must be configured for Graph token.");
        }

        try {
            String tokenEndpoint = "https://login.microsoftonline.com/"
                    + URLEncoder.encode(tenantId, StandardCharsets.UTF_8)
                    + "/oauth2/v2.0/token";

            String body = "client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&grant_type=client_credentials"
                    + "&scope=" + urlEncode(GRAPH_TOKEN_SCOPE);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenEndpoint))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("Graph token request failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            TokenResponse tokenResponse =
                    objectMapper.readValue(response.body(), TokenResponse.class);

            if (tokenResponse.access_token == null || tokenResponse.access_token.isEmpty()) {
                throw new RuntimeException("Graph token response did not contain access_token.");
            }

            long expiresIn = tokenResponse.expires_in != null ? tokenResponse.expires_in : 3600L;
            cachedGraphToken = tokenResponse.access_token;
            cachedGraphTokenExpiresAt = now.plusSeconds(expiresIn);

            return cachedGraphToken;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error acquiring Graph access token", e);
        }
    }

    // ---------------------------------------------------------------------
    // Entra Agent Identity lookup via Graph beta API
    // ---------------------------------------------------------------------

    /**
     * Lazily load all agent identity service principals from Microsoft Graph beta API.
     * Builds a map of displayName to list of Entra object IDs.
     *
     * <p>Uses: GET https://graph.microsoft.com/beta/servicePrincipals/microsoft.graph.agentIdentity
     *              ?$select=id,displayName
     *
     * <p>Best-effort: on any failure, logs a warning and returns an empty map.
     */
    private synchronized java.util.Map<String, java.util.List<String>> loadAgentIdentityMap() {
        if (agentIdentityByDisplayName != null) {
            return agentIdentityByDisplayName;
        }

        java.util.Map<String, java.util.List<String>> map = new java.util.HashMap<>();

        try {
            String graphToken = acquireGraphBearerToken();
            String url = "https://graph.microsoft.com/beta/servicePrincipals/microsoft.graph.agentIdentity"
                    + "?$select=id,displayName&$top=999";

            while (url != null) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(requestTimeout)
                        .header("Authorization", "Bearer " + graphToken)
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    System.err.println("Graph agentIdentity list failed: HTTP "
                            + response.statusCode() + " body=" + response.body());
                    agentIdentityByDisplayName = java.util.Collections.emptyMap();
                    return agentIdentityByDisplayName;
                }

                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());

                if (root.has("value") && root.get("value").isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode node : root.get("value")) {
                        String displayName = optText(node, "displayName");
                        String objectId = optText(node, "id");
                        if (displayName != null && objectId != null) {
                            map.computeIfAbsent(displayName, k -> new java.util.ArrayList<>()).add(objectId);
                        }
                    }
                }

                // OData pagination
                com.fasterxml.jackson.databind.JsonNode nextLink = root.get("@odata.nextLink");
                url = (nextLink != null && !nextLink.isNull()) ? nextLink.asText() : null;
            }
        } catch (Exception e) {
            System.err.println("Failed to load agent identity map from Graph: " + e.getMessage());
            agentIdentityByDisplayName = java.util.Collections.emptyMap();
            return agentIdentityByDisplayName;
        }

        agentIdentityByDisplayName = java.util.Collections.unmodifiableMap(map);
        return agentIdentityByDisplayName;
    }

    /**
     * Look up the Entra object ID for an agent identity by display name.
     *
     * <p>Returns the Entra object ID if exactly one agent identity matches
     * the given display name. Returns null if no match, multiple matches
     * (ambiguous), or the identity map failed to load.
     *
     * <p><b>Note:</b> displayName is the only viable correlation field between
     * Foundry agents and Entra agent identities. DisplayNames are NOT
     * guaranteed to be unique. This method is strictly best-effort.
     *
     * @param agentDisplayName the Foundry agent name
     * @return Entra object ID (GUID) or null
     */
    public String getEntraAgentObjectId(String agentDisplayName) {
        if (agentDisplayName == null || agentDisplayName.isEmpty()) {
            return null;
        }

        java.util.Map<String, java.util.List<String>> map = loadAgentIdentityMap();
        java.util.List<String> ids = map.get(agentDisplayName);

        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() > 1) {
            System.err.println("Ambiguous Entra agent identity match for displayName='"
                    + agentDisplayName + "': " + ids.size() + " matches found. Skipping.");
            return null;
        }

        return ids.get(0);
    }

    private HttpRequest.Builder baseRequestBuilder(String pathAndQuery) {
        String url = agentServiceEndpoint + pathAndQuery;
        String token = acquireBearerToken();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");

        return builder;
    }

    // ---------------------------------------------------------------------
    // Agent inventory API
    // ---------------------------------------------------------------------

    /**
     * List all agents for this project endpoint, paging until has_more == false.
     * <p>
     * Uses:
     * GET {endpoint}/agents?api-version=v1&limit={limit}&order=asc&after={last_id}
     */
    public List<AzureAgentDescriptor> listAgents() {
        return listAgents("/agents", API_VERSION);
    }

    /**
     * List agents using an explicitly provided base path and api-version.
     *
     * @param agentBasePath "/agents" (classic) or "/assistants" (new)
     * @param apiVersion    "v1" (or other configured value)
     */
    public List<AzureAgentDescriptor> listAgents(String agentBasePath, String apiVersion) {
        List<AzureAgentDescriptor> all = new ArrayList<>();

        String continuationToken = null;
        int pageSize = 50; // reasonable default
        System.out.println("Listing agents with pageSize="+pageSize);
        do {
            ListAgentsPage page = listAgentsPaginated(pageSize, continuationToken, agentBasePath, apiVersion);
            System.out.println("Got page with "+page.getAgents().size()+" agents");
            all.addAll(page.getAgents());
            continuationToken = page.getContinuationToken();
        } while (continuationToken != null);

        return all;
    }

    /**
     * Paginated listing using 'limit' and 'after' based on the sample response.
     *
     * @param maxResults        per-page limit (limit query parameter)
     * @param continuationToken if non-null, passed as 'after' (last_id from previous page)
     */
    public ListAgentsPage listAgentsPaginated(int maxResults, String continuationToken, String agentBasePath, String apiVersion) {

        StringBuilder query = new StringBuilder(agentBasePath).append("?api-version=")
                .append(apiVersion)
                .append("&limit=").append(maxResults)
                .append("&order=asc");

        if (continuationToken != null && !continuationToken.isEmpty()) {
            query.append("&after=").append(urlEncode(continuationToken));
        }

        try {
            HttpRequest request = baseRequestBuilder(query.toString())
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("listAgentsPaginated failed: HTTP "
                        + response.statusCode() + " body=" + response.body());
            }

            AssistantsListResponse list =
                    objectMapper.readValue(response.body(), AssistantsListResponse.class);

            List<AzureAgentDescriptor> agents = list.toDescriptors();

            String nextToken = (list.has_more != null && list.has_more)
                    ? list.last_id
                    : null;

            return new ListAgentsPage(agents, nextToken);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error calling listAgentsPaginated()", e);
        }
    }

    /**
     * Get a single agent by id.
     * <p>
     * For now we implement this by listing and filtering, to avoid guessing
     * the exact GET /agents/{id} shape. You can replace this with a direct
     * GET call later.
     */
    public AzureAgentDescriptor getAgent(String agentId) {
        return getAgent(agentId, "/agents", API_VERSION);
    }

    /**
     * Get an agent from the list result using an explicitly provided base path and api-version.
     *
     * This preserves the existing behavior (no per-agent GET call).
     */
    public AzureAgentDescriptor getAgent(String agentId, String agentBasePath, String apiVersion) {
        return listAgents(agentBasePath, apiVersion).stream()
                .filter(a -> agentId.equals(a.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Delete an agent by ID.
     *
     * @param agentId       the agent/assistant ID to delete
     * @param agentBasePath "/agents" (classic) or "/assistants" (new)
     * @param apiVersion    configured API version
     * @throws RuntimeException on HTTP error or I/O failure
     */
    public void deleteAgent(String agentId, String agentBasePath, String apiVersion) {
        try {
            String pathAndQuery = agentBasePath + "/" + urlEncode(agentId)
                    + "?api-version=" + urlEncode(apiVersion);

            HttpRequest request = baseRequestBuilder(pathAndQuery)
                    .DELETE()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                        "Delete agent failed: HTTP " + response.statusCode()
                                + " body=" + response.body());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error deleting agent " + agentId, e);
        }
    }

    public java.util.List<String> getKnowledgeBaseIdsForAgent(String agentId) {
        if (agentId == null) {
            return java.util.Collections.emptyList();
        }
        java.util.Map<String, AzureAgentRelations> map = loadAgentRelationsFromInventory();
        AzureAgentRelations rel = map.get(agentId);
        return rel != null ? rel.getKnowledgeBaseIds() : java.util.Collections.emptyList();
    }

    public String getGuardrailIdForAgent(String agentId) {
        if (agentId == null) {
            return null;
        }
        java.util.Map<String, AzureAgentRelations> map = loadAgentRelationsFromInventory();
        AzureAgentRelations rel = map.get(agentId);
        if (rel == null || rel.getGuardrailIds().isEmpty()) {
            return null;
        }
        // Azure currently supports only one guardrail per agent;
        // if the inventory ever has more, we take the first.
        return rel.getGuardrailIds().get(0);
    }

    public java.util.List<String> getToolIdsForAgent(String agentId) {
        if (agentId == null) {
            return java.util.Collections.emptyList();
        }
        java.util.Map<String, AzureAgentRelations> map = loadAgentRelationsFromInventory();
        AzureAgentRelations rel = map.get(agentId);
        return rel != null ? rel.getToolIds() : java.util.Collections.emptyList();
    }

    // ---------------------------------------------------------------------
    // Tools inventory: single-fetch loader + section accessors
    // ---------------------------------------------------------------------

    /**
     * Fetch the inventory JSON document once and populate all section caches:
     * tools, knowledge bases, guardrails, agent relations, identity bindings.
     *
     * Subsequent calls are no-ops (all caches already set).
     */
    private synchronized void loadFullInventory() {
        // If any cache is already populated, all were set together — skip.
        if (cachedToolsById != null) {
            return;
        }

        if ((toolsInventoryUrl == null || toolsInventoryUrl.isEmpty()) &&
                (toolsInventoryFilePath == null || toolsInventoryFilePath.isEmpty())) {
            cachedToolsById = Collections.emptyMap();
            cachedKnowledgeBases = Collections.emptyList();
            cachedGuardrails = Collections.emptyList();
            cachedIdentityBindings = Collections.emptyList();
            cachedAgentRelations = Collections.emptyMap();
            return;
        }

        try {
            String json = fetchInventoryJson();
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);

            // --- Tools ---
            Map<String, AzureToolDescriptor> toolsMap = new HashMap<>();
            if (root.has("tools") && root.get("tools").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("tools")) {
                    parseToolNode(node, toolsMap);
                }
            }
            cachedToolsById = Collections.unmodifiableMap(toolsMap);

            // --- Knowledge bases ---
            List<AzureKnowledgeBaseDescriptor> kbList = new ArrayList<>();
            if (root.has("knowledgeBases") && root.get("knowledgeBases").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("knowledgeBases")) {
                    parseKnowledgeBaseNode(node, kbList);
                }
            }
            cachedKnowledgeBases = Collections.unmodifiableList(kbList);

            // --- Guardrails ---
            List<AzureGuardrailDescriptor> grList = new ArrayList<>();
            if (root.has("guardrails") && root.get("guardrails").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("guardrails")) {
                    parseGuardrailNode(node, grList);
                }
            }
            cachedGuardrails = Collections.unmodifiableList(grList);

            // --- Agent relations ---
            Map<String, AzureAgentRelations> relMap = new HashMap<>();
            if (root.has("agents") && root.get("agents").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("agents")) {
                    parseAgentRelationsNode(node, relMap);
                }
            }
            cachedAgentRelations = Collections.unmodifiableMap(relMap);

            // --- Identity bindings ---
            List<AzureIdentityBindingDescriptor> ibList = new ArrayList<>();
            if (root.has("identityBindings") && root.get("identityBindings").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("identityBindings")) {
                    parseIdentityBindingNode(node, ibList);
                }
            }
            cachedIdentityBindings = Collections.unmodifiableList(ibList);

        } catch (Exception e) {
            System.err.println("Failed to load tools inventory: " + e.getMessage());
            e.printStackTrace();
            cachedToolsById = Collections.emptyMap();
            cachedKnowledgeBases = Collections.emptyList();
            cachedGuardrails = Collections.emptyList();
            cachedIdentityBindings = Collections.emptyList();
            cachedAgentRelations = Collections.emptyMap();
        }
    }

    /**
     * Fetch the raw inventory JSON string from URL or local file.
     */
    private String fetchInventoryJson() throws IOException, InterruptedException {
        if (toolsInventoryUrl != null && !toolsInventoryUrl.isEmpty()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(toolsInventoryUrl))
                    .GET()
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new IOException("Failed to fetch tools inventory from URL " +
                        toolsInventoryUrl + " status=" + response.statusCode());
            }
            return response.body();
        }

        return new String(
                java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(toolsInventoryFilePath)));
    }

    private synchronized Map<String, AzureToolDescriptor> loadToolsInventory() {
        if (cachedToolsById == null) {
            loadFullInventory();
        }
        return cachedToolsById;
    }

    private List<AzureKnowledgeBaseDescriptor> loadKnowledgeBasesInventory() {
        if (cachedKnowledgeBases == null) {
            loadFullInventory();
        }
        return cachedKnowledgeBases;
    }

    private List<AzureGuardrailDescriptor> loadGuardrailsInventory() {
        if (cachedGuardrails == null) {
            loadFullInventory();
        }
        return cachedGuardrails;
    }

    private Map<String, AzureAgentRelations> loadAgentRelationsFromInventory() {
        if (cachedAgentRelations == null) {
            loadFullInventory();
        }
        return cachedAgentRelations;
    }

    private List<AzureIdentityBindingDescriptor> loadIdentityBindingsInventory() {
        if (cachedIdentityBindings == null) {
            loadFullInventory();
        }
        return cachedIdentityBindings;
    }

    // ---------------------------------------------------------------------
    // Inventory JSON node parsers
    // ---------------------------------------------------------------------

    /**
     * Parse a tool node from the JSON inventory.
     * Handles multiple tool types with different nested structures:
     * - file_search/code_interpreter: minimal fields
     * - openapi: name/description in definition.openapi
     * - connected_agent: name/description in definition.connected_agent
     */
    private void parseToolNode(com.fasterxml.jackson.databind.JsonNode node,
                               Map<String, AzureToolDescriptor> out) {
        String id = optText(node, "id");
        if (id == null || id.isEmpty()) {
            return;
        }

        String type = optText(node, "type");

        // Extract name, description, endpoint based on tool type
        String name = null;
        String description = null;
        String endpoint = null;
        String definition = null;

        // Check if we have a definition node
        com.fasterxml.jackson.databind.JsonNode defNode = node.get("definition");

        if (defNode != null && !defNode.isNull()) {
            // Serialize the entire definition as a JSON string
            try {
                definition = objectMapper.writeValueAsString(defNode);
            } catch (Exception e) {
                // Fallback to toString if serialization fails
                definition = defNode.toString();
            }

            // Extract type-specific fields from nested definition
            if ("openapi".equals(type) && defNode.has("openapi")) {
                com.fasterxml.jackson.databind.JsonNode openapiNode = defNode.get("openapi");
                name = optText(openapiNode, "name");
                description = optText(openapiNode, "description");

                // Extract server URL as endpoint if available
                if (openapiNode.has("spec")) {
                    com.fasterxml.jackson.databind.JsonNode specNode = openapiNode.get("spec");
                    if (specNode.has("servers") && specNode.get("servers").isArray()
                            && specNode.get("servers").size() > 0) {
                        com.fasterxml.jackson.databind.JsonNode firstServer = specNode.get("servers").get(0);
                        endpoint = optText(firstServer, "url");
                    }
                }
            } else if ("connected_agent".equals(type) && defNode.has("connected_agent")) {
                com.fasterxml.jackson.databind.JsonNode connectedAgentNode = defNode.get("connected_agent");
                name = optText(connectedAgentNode, "name");
                description = optText(connectedAgentNode, "description");
                // For connected agents, the agent ID could serve as an endpoint reference
                String connectedAgentId = optText(connectedAgentNode, "id");
                if (connectedAgentId != null) {
                    endpoint = "agent://" + connectedAgentId;
                }
            }
            // For file_search, code_interpreter: leave name/description/endpoint as null
            // They only have type and minimal definition
        }

        // Fallback: use id as name if name is still null
        if (name == null || name.isEmpty()) {
            name = id;
        }

        AzureToolDescriptor tool = new AzureToolDescriptor(
                id,
                name,
                type,
                description,
                endpoint,
                definition
        );

        out.put(id, tool);
    }

    private void parseKnowledgeBaseNode(com.fasterxml.jackson.databind.JsonNode node,
                                        java.util.List<AzureKnowledgeBaseDescriptor> out) {
        String id = optText(node, "id");
        if (id == null || id.isEmpty()) {
            return;
        }

        // Try to get a human-friendly name; fall back to id
        String name = optText(node, "serverLabel");
        if (name == null || name.isEmpty()) {
            name = id;
        }

        // Knowledge bases are mcp tools; treat that as sourceType for now
        String sourceType = optText(node, "type");
        if (sourceType == null || sourceType.isEmpty()) {
            sourceType = "mcp";
        }

        String connectionRef = optText(node, "projectConnectionId");
        // Inventory doesn't currently include a KB status; keep it nullable
        String status = optText(node, "status");

        out.add(new AzureKnowledgeBaseDescriptor(
                id,
                name,
                sourceType,
                connectionRef,
                status
        ));
    }

    private void parseGuardrailNode(com.fasterxml.jackson.databind.JsonNode node,
                                    java.util.List<AzureGuardrailDescriptor> out) {
        String id = optText(node, "id");
        if (id == null || id.isEmpty()) {
            return;
        }

        String raiPolicyName = optText(node, "raiPolicyName");

        java.util.List<String> agents = new java.util.ArrayList<>();
        if (node.has("agents") && node.get("agents").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode a : node.get("agents")) {
                if (a.isTextual()) {
                    agents.add(a.asText());
                }
            }
        }

        com.fasterxml.jackson.databind.JsonNode def = node.has("definition")
                ? node.get("definition")
                : null;

        out.add(new AzureGuardrailDescriptor(
                id,
                raiPolicyName,
                agents,
                def
        ));
    }

    private void parseAgentRelationsNode(com.fasterxml.jackson.databind.JsonNode node,
                                         java.util.Map<String, AzureAgentRelations> out) {
        String agentId = optText(node, "agentId");
        if (agentId == null || agentId.isEmpty()) {
            return;
        }

        java.util.List<String> toolIds = new java.util.ArrayList<>();
        java.util.List<String> knowledgeBaseIds = new java.util.ArrayList<>();
        java.util.List<String> guardrailIds = new java.util.ArrayList<>();

        // tools: [ { "id": "tool:..." }, ... ]
        if (node.has("tools") && node.get("tools").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode t : node.get("tools")) {
                if (t.isTextual()) {
                    toolIds.add(t.asText());
                } else if (t.has("id") && t.get("id").isTextual()) {
                    toolIds.add(t.get("id").asText());
                }
            }
        }

        // knowledgeBases: [ { "id": "kb:..." }, ... ]
        if (node.has("knowledgeBases") && node.get("knowledgeBases").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode kb : node.get("knowledgeBases")) {
                if (kb.isTextual()) {
                    knowledgeBaseIds.add(kb.asText());
                } else if (kb.has("id") && kb.get("id").isTextual()) {
                    knowledgeBaseIds.add(kb.get("id").asText());
                }
            }
        }

        // guardrails: [ { "id": "guardrail:..." } ]
        if (node.has("guardrails") && node.get("guardrails").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode gr : node.get("guardrails")) {
                if (gr.isTextual()) {
                    guardrailIds.add(gr.asText());
                } else if (gr.has("id") && gr.get("id").isTextual()) {
                    guardrailIds.add(gr.get("id").asText());
                }
            }
        }

        out.put(agentId, new AzureAgentRelations(toolIds, knowledgeBaseIds, guardrailIds));
    }

    private void parseIdentityBindingNode(com.fasterxml.jackson.databind.JsonNode node,
                                          java.util.List<AzureIdentityBindingDescriptor> out) {
        String id = optText(node, "id");
        if (id == null || id.isEmpty()) {
            return;
        }

        String agentId = optText(node, "agentId");
        String agentVersion = optText(node, "agentVersion");
        String scope = optText(node, "scope");
        String scopeResourceId = optText(node, "scopeResourceId");
        String principalId = optText(node, "principalId");
        String principalType = optText(node, "principalType");
        String roleDefinitionId = optText(node, "roleDefinitionId");
        String roleName = optText(node, "roleName");

        java.util.List<String> permissions = new java.util.ArrayList<>();
        if (node.has("permissions") && node.get("permissions").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode p : node.get("permissions")) {
                if (p.isTextual()) {
                    permissions.add(p.asText());
                }
            }
        }

        out.add(new AzureIdentityBindingDescriptor(
                id,
                agentId,
                agentVersion,
                scope,
                scopeResourceId,
                principalId,
                principalType,
                roleDefinitionId,
                roleName,
                permissions
        ));
    }

    private String optText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    // ---------------------------------------------------------------------
    // Public inventory accessors
    // ---------------------------------------------------------------------

    public List<AzureToolDescriptor> listAllTools() {
        return new ArrayList<>(loadToolsInventory().values());
    }

    public List<AzureKnowledgeBaseDescriptor> listAllKnowledgeBases() {
        return loadKnowledgeBasesInventory();
    }

    public java.util.List<AzureGuardrailDescriptor> listAllGuardrails() {
        return loadGuardrailsInventory();
    }

    public java.util.List<AzureIdentityBindingDescriptor> listAllIdentityBindings() {
        return loadIdentityBindingsInventory();
    }

    public java.util.List<AzureIdentityBindingDescriptor> listAgentIdentityBindings(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        java.util.List<AzureIdentityBindingDescriptor> allBindings = loadIdentityBindingsInventory();
        java.util.List<AzureIdentityBindingDescriptor> result = new java.util.ArrayList<>();

        for (AzureIdentityBindingDescriptor binding : allBindings) {
            if (agentId.equals(binding.getAgentId())) {
                result.add(binding);
            }
        }

        return result;
    }

    /**
     * Get tools for a specific agent using the agent relations mapping.
     */
    public List<AzureToolDescriptor> listAgentActionGroups(String agentId,
                                                           String agentVersion) {
        if (agentId == null || agentId.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, AzureToolDescriptor> toolsById = loadToolsInventory();
        if (toolsById.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> toolIds = getToolIdsForAgent(agentId);
        if (toolIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<AzureToolDescriptor> result = new ArrayList<>();
        for (String toolId : toolIds) {
            AzureToolDescriptor tool = toolsById.get(toolId);
            if (tool != null) {
                result.add(tool);
            }
        }
        return result;
    }


    public AzureToolDescriptor getAgentActionGroup(String agentId,
                                                   String agentVersion,
                                                   String actionGroupId) {
        if (actionGroupId == null || actionGroupId.isEmpty()) {
            return null;
        }

        Map<String, AzureToolDescriptor> toolsById = loadToolsInventory();
        return toolsById.get(actionGroupId);
    }

    public List<AzureKnowledgeBaseDescriptor> listAgentKnowledgeBases(String agentId,
                                                                      String agentVersion) {
        if (agentId == null || agentId.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> kbIds = getKnowledgeBaseIdsForAgent(agentId);
        if (kbIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Build map of KB descriptors by ID
        Map<String, AzureKnowledgeBaseDescriptor> kbsById = new HashMap<>();
        for (AzureKnowledgeBaseDescriptor kb : loadKnowledgeBasesInventory()) {
            kbsById.put(kb.getId(), kb);
        }

        // Look up KB descriptors for this agent
        List<AzureKnowledgeBaseDescriptor> result = new ArrayList<>();
        for (String kbId : kbIds) {
            AzureKnowledgeBaseDescriptor kb = kbsById.get(kbId);
            if (kb != null) {
                result.add(kb);
            }
        }
        return result;
    }

    public AzureGuardrailDescriptor getGuardrail(String guardrailId,
                                                 String guardrailVersion) {
        if (guardrailId == null || guardrailId.isEmpty()) {
            return null;
        }

        List<AzureGuardrailDescriptor> guardrails = loadGuardrailsInventory();
        for (AzureGuardrailDescriptor gr : guardrails) {
            if (guardrailId.equals(gr.getId())) {
                return gr;
            }
        }
        return null;
    }

    public List<AzureRoleAssignmentDescriptor> listRoleAssignmentsForScope(String scope) {
        // NOTE: Identity bindings are now loaded from JSON inventory via listAllIdentityBindings()
        // This method is kept for potential future Azure ARM RBAC API integration
        return Collections.emptyList();
    }

    public List<AzureRoleDefinitionDescriptor> getRoleDefinitions(Set<String> roleDefinitionIds) {
        // TODO: ARM RBAC RoleDefinitions API
        return Collections.emptyList();
    }

    @Override
    public void close() throws IOException {

    }
}