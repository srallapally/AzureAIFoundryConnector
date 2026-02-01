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

    // Cached tools inventory
    private List<AzureToolDescriptor> cachedToolsInventory;
    // Cached knowledge base inventory
    private List<AzureKnowledgeBaseDescriptor> cachedKnowledgeBases;
    // Cached guardrail inventory
    private java.util.List<AzureGuardrailDescriptor> cachedGuardrails;
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
    private static final String API_VERSION = "v1";

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
        List<AzureAgentDescriptor> all = new ArrayList<>();

        String continuationToken = null;
        int pageSize = 50; // reasonable default

        do {
            ListAgentsPage page = listAgentsPaginated(pageSize, continuationToken);
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
    public ListAgentsPage listAgentsPaginated(int maxResults, String continuationToken) {

        StringBuilder query = new StringBuilder("/agents?api-version=")
                .append(API_VERSION)
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
        return listAgents().stream()
                .filter(a -> agentId.equals(a.getId()))
                .findFirst()
                .orElse(null);
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
    // Tools / KB / Guardrails / RBAC placeholders
    // ---------------------------------------------------------------------
    private synchronized List<AzureToolDescriptor> loadToolsInventory() {
        if (cachedToolsInventory != null) {
            return cachedToolsInventory;
        }

        if ((toolsInventoryUrl == null || toolsInventoryUrl.isEmpty()) &&
                (toolsInventoryFilePath == null || toolsInventoryFilePath.isEmpty())) {
            // Inventory disabled / not configured
            cachedToolsInventory = Collections.emptyList();
            return cachedToolsInventory;
        }

        try {
            String json;
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
                json = response.body();
            } else {
                // Local file path (for local testing/dev)
                json = new String(
                        java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(toolsInventoryFilePath)));
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            List<AzureToolDescriptor> result = new ArrayList<>();

            if (root.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root) {
                    parseToolNode(node, result);
                }
            } else if (root.has("tools") && root.get("tools").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("tools")) {
                    parseToolNode(node, result);
                }
            } else {
                // Single object case – treat root as one tool record
                parseToolNode(root, result);
            }

            cachedToolsInventory = Collections.unmodifiableList(result);
            return cachedToolsInventory;
        } catch (Exception e) {
            // Fail-safe: log to stderr and return empty list. We don't want to
            // break the whole connector if the inventory is malformed/unreachable.
            System.err.println("Failed to load tools inventory: " + e.getMessage());
            cachedToolsInventory = Collections.emptyList();
            return cachedToolsInventory;
        }
    }
    private List<AzureKnowledgeBaseDescriptor> loadKnowledgeBasesInventory() {
        if (cachedKnowledgeBases != null) {
            return cachedKnowledgeBases;
        }

        if ((toolsInventoryUrl == null || toolsInventoryUrl.isEmpty()) &&
                (toolsInventoryFilePath == null || toolsInventoryFilePath.isEmpty())) {
            // Inventory disabled / not configured
            cachedKnowledgeBases = java.util.Collections.emptyList();
            return cachedKnowledgeBases;
        }

        try {
            String json;
            if (toolsInventoryUrl != null && !toolsInventoryUrl.isEmpty()) {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(toolsInventoryUrl))
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                java.net.http.HttpResponse<String> response = httpClient.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    throw new RuntimeException("Non-2xx response from tools inventory URL: "
                            + response.statusCode() + " " + response.body());
                }

                json = response.body();
            } else {
                // Local file path (for local testing/dev)
                json = new String(
                        java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(toolsInventoryFilePath)));
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            java.util.List<AzureKnowledgeBaseDescriptor> result = new java.util.ArrayList<>();

            if (root.has("knowledgeBases") && root.get("knowledgeBases").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("knowledgeBases")) {
                    parseKnowledgeBaseNode(node, result);
                }
            } else if (root.isArray()) {
                // Unlikely for KBs, but be defensive
                for (com.fasterxml.jackson.databind.JsonNode node : root) {
                    parseKnowledgeBaseNode(node, result);
                }
            } else if (root.has("id") && root.has("serverUrl")) {
                // Single object case – treat root as one KB record
                parseKnowledgeBaseNode(root, result);
            }

            cachedKnowledgeBases = java.util.Collections.unmodifiableList(result);
            return cachedKnowledgeBases;
        } catch (Exception e) {
            System.err.println("Failed to load knowledge base inventory: " + e.getMessage());
            cachedKnowledgeBases = java.util.Collections.emptyList();
            return cachedKnowledgeBases;
        }
    }
    private java.util.List<AzureGuardrailDescriptor> loadGuardrailsInventory() {
        if (cachedGuardrails != null) {
            return cachedGuardrails;
        }

        if ((toolsInventoryUrl == null || toolsInventoryUrl.isEmpty()) &&
                (toolsInventoryFilePath == null || toolsInventoryFilePath.isEmpty())) {
            cachedGuardrails = java.util.Collections.emptyList();
            return cachedGuardrails;
        }

        try {
            String json;
            if (toolsInventoryUrl != null && !toolsInventoryUrl.isEmpty()) {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(toolsInventoryUrl))
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                java.net.http.HttpResponse<String> response = httpClient.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    throw new RuntimeException("Non-2xx response from tools inventory URL: "
                            + response.statusCode() + " " + response.body());
                }

                json = response.body();
            } else {
                json = new String(
                        java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(toolsInventoryFilePath)));
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            java.util.List<AzureGuardrailDescriptor> result = new java.util.ArrayList<>();

            if (root.has("guardrails") && root.get("guardrails").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("guardrails")) {
                    parseGuardrailNode(node, result);
                }
            } else if (root.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root) {
                    parseGuardrailNode(node, result);
                }
            } else if (root.has("id") && root.has("raiPolicyName")) {
                parseGuardrailNode(root, result);
            }

            cachedGuardrails = java.util.Collections.unmodifiableList(result);
            return cachedGuardrails;
        } catch (Exception e) {
            System.err.println("Failed to load guardrail inventory: " + e.getMessage());
            cachedGuardrails = java.util.Collections.emptyList();
            return cachedGuardrails;
        }
    }
    private java.util.Map<String, AzureAgentRelations> loadAgentRelationsFromInventory() {
        if (cachedAgentRelations != null) {
            return cachedAgentRelations;
        }

        java.util.Map<String, AzureAgentRelations> map = new java.util.HashMap<>();

        if ((toolsInventoryUrl == null || toolsInventoryUrl.isEmpty()) &&
                (toolsInventoryFilePath == null || toolsInventoryFilePath.isEmpty())) {
            cachedAgentRelations = java.util.Collections.emptyMap();
            return cachedAgentRelations;
        }

        try {
            String json;
            if (toolsInventoryUrl != null && !toolsInventoryUrl.isEmpty()) {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(toolsInventoryUrl))
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                java.net.http.HttpResponse<String> response = httpClient.send(
                        request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() / 100 != 2) {
                    throw new RuntimeException("Non-2xx response from tools inventory URL: "
                            + response.statusCode() + " " + response.body());
                }

                json = response.body();
            } else {
                json = new String(
                        java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(toolsInventoryFilePath)));
            }

            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);

            if (root.has("agents") && root.get("agents").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : root.get("agents")) {
                    parseAgentRelationsNode(node, map);
                }
            }

            cachedAgentRelations = java.util.Collections.unmodifiableMap(map);
            return cachedAgentRelations;
        } catch (Exception e) {
            System.err.println("Failed to load agent relations from inventory: " + e.getMessage());
            cachedAgentRelations = java.util.Collections.emptyMap();
            return cachedAgentRelations;
        }
    }


    private void parseToolNode(com.fasterxml.jackson.databind.JsonNode node,
                               List<AzureToolDescriptor> out) {
        String id = optText(node, "id");
        String agentId = optText(node, "agentId");

        if (id == null || id.isEmpty() || agentId == null || agentId.isEmpty()) {
            // We require both IDs; skip malformed entries
            return;
        }

        String name = optText(node, "name");
        String type = optText(node, "type");
        String description = optText(node, "description");
        String endpoint = optText(node, "endpoint");
        String definition = optText(node, "definition");

        out.add(new AzureToolDescriptor(
                id,
                agentId,
                (name != null && !name.isEmpty()) ? name : id,
                type,
                description,
                endpoint,
                definition
        ));
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
        // Inventory doesn’t currently include a KB status; keep it nullable
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


    private String optText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    public List<AzureToolDescriptor> listAllTools() {
        return loadToolsInventory();
    }

    public List<AzureKnowledgeBaseDescriptor> listAllKnowledgeBases() {
        return loadKnowledgeBasesInventory();
    }

    public java.util.List<AzureGuardrailDescriptor> listAllGuardrails() {
        return loadGuardrailsInventory();
    }

    public List<AzureToolDescriptor> listAgentActionGroups(String agentId,
                                                           String agentVersion) {
        List<AzureToolDescriptor> allTools = loadToolsInventory();
        if (allTools.isEmpty() || agentId == null || agentId.isEmpty()) {
            return Collections.emptyList();
        }

        List<AzureToolDescriptor> result = new ArrayList<>();
        for (AzureToolDescriptor tool : allTools) {
            if (tool != null && agentId.equals(tool.getAgentId())) {
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

        // Delegate to listAgentActionGroups; once that method is backed by the
        // tools inventory, this will automatically return the right descriptor.
        java.util.List<AzureToolDescriptor> tools = listAgentActionGroups(agentId, agentVersion);
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        for (AzureToolDescriptor tool : tools) {
            if (tool != null && actionGroupId.equals(tool.getId())) {
                return tool;
            }
        }

        return null;
    }

    public List<AzureKnowledgeBaseDescriptor> listAgentKnowledgeBases(String agentId,
                                                                      String agentVersion) {
        // TODO: derive from file_search / azure_ai_search / memory_search tools.
        return Collections.emptyList();
    }

    public AzureGuardrailDescriptor getGuardrail(String guardrailId,
                                                 String guardrailVersion) {
        // TODO: derive from rai_config or dedicated guardrail APIs if/when used.
        return null;
    }

    public List<AzureRoleAssignmentDescriptor> listRoleAssignmentsForScope(String scope) {
        // TODO: ARM RBAC RoleAssignments API
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
