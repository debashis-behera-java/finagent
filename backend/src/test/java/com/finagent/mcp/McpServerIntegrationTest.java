package com.finagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end MCP verification over the real Streamable HTTP endpoint (/mcp, RANDOM_PORT):
 * initialize -> initialized -> tools/list -> tools/call for each finance tool.
 * Backed by the stub providers, so it requires no PostgreSQL, Docker, internet or API keys.
 *
 * <p>Phase 16: /mcp requires a Bearer token. The test registers a throwaway
 * account over HTTP and uses its JWT for every JSON-RPC call (proving the
 * authenticated path); {@link McpUnauthorizedTest} proves anonymous calls fail.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpServerIntegrationTest {

    private static final String MCP_ENDPOINT = "/mcp";

    @LocalServerPort
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient client;
    private String sessionId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        accessToken = registerAndLogin();
    }

    /** Throwaway account per test — no shared state, no rate-limit interference. */
    private String registerAndLogin() {
        String email = "mcp-" + java.util.UUID.randomUUID() + "@example.com";
        String body = client.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}")
                .retrieve()
                .body(String.class);
        try {
            return objectMapper.readTree(body).path("accessToken").asText();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not obtain MCP test token", ex);
        }
    }

    @Test
    void mcpServerAdvertisesAndInvokesFinanceTools() throws Exception {
        // 1. initialize -> server identity + tool capability + session
        JsonNode init = postJson("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"finagent-mcp-test","version":"1.0.0"}}}""");
        assertThat(init).isNotNull();
        assertThat(init.path("result").path("serverInfo").path("name").asText()).isEqualTo("finagent");
        assertThat(init.path("result").path("serverInfo").path("version").asText()).isEqualTo("1.0.0");
        assertThat(init.path("result").path("capabilities").path("tools").isMissingNode()).isFalse();

        // 2. initialized notification
        postJson("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        // 3. tools/list -> the four finance tools are advertised (registered at startup)
        JsonNode list = postJson("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        List<String> names = new ArrayList<>();
        list.path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        assertThat(names)
                .contains("get_stock_price", "get_stock_history", "get_stock_fundamentals", "search_financial_news", "analyze_stock_risk", "analyze_news_sentiment")
                .doesNotHaveDuplicates();
        list.path("result").path("tools").forEach(tool -> {
            if (names.contains(tool.path("name").asText())) {
                assertThat(tool.path("description").asText()).isNotBlank();
            }
        });

        // 4. get_stock_price (stub provider - no external API)
        JsonNode price = postJson("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"get_stock_price","arguments":{"symbol":"AAPL"}}}""");
        assertThat(price.path("result").path("content")).isNotEmpty();
        assertThat(price.path("result").path("isError").asBoolean()).isFalse();
        String priceText = firstTextContent(price);
        assertThat(priceText).contains("\"symbol\":\"AAPL\"").contains("\"price\"");

        // 5. get_stock_history
        JsonNode history = postJson("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"get_stock_history","arguments":{"symbol":"AAPL","from":"2026-08-01","to":"2026-08-31"}}}""");
        assertThat(history.path("result").path("isError").asBoolean()).isFalse();
        String historyText = firstTextContent(history);
        assertThat(historyText).contains("\"symbol\":\"AAPL\"").contains("\"barCount\"");

        // 6. get_stock_fundamentals
        JsonNode fundamentals = postJson("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                 "params":{"name":"get_stock_fundamentals","arguments":{"symbol":"AAPL"}}}""");
        assertThat(fundamentals.path("result").path("isError").asBoolean()).isFalse();
        String fundamentalsText = firstTextContent(fundamentals);
        assertThat(fundamentalsText).contains("\"symbol\":\"AAPL\"").contains("\"companyName\"");

        // 7. search_financial_news (no sentiment - pure retrieval)
        JsonNode news = postJson("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call",
                 "params":{"name":"search_financial_news","arguments":{"symbol":"AAPL","limit":3}}}""");
        assertThat(news.path("result").path("isError").asBoolean()).isFalse();
        String newsText = firstTextContent(news);
        assertThat(newsText).contains("\"symbol\":\"AAPL\"").contains("\"count\":3");

        // 8. analyze_stock_risk (deterministic risk engine; Sharpe unavailable as no risk-free rate is configured)
        JsonNode risk = postJson("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call",
                 "params":{"name":"analyze_stock_risk","arguments":{"symbol":"AAPL","from":"2026-08-01","to":"2026-08-31"}}}""");
        assertThat(risk.path("result").path("isError").asBoolean()).isFalse();
        String riskText = firstTextContent(risk);
        assertThat(riskText).contains("\"symbol\":\"AAPL\"").contains("\"result\"");

        // 8b. analyze_news_sentiment (deterministic lexicon over stub headlines)
        JsonNode sentiment = postJson("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call",
                 "params":{"name":"analyze_news_sentiment","arguments":{"symbol":"AAPL","limit":5}}}""");
        assertThat(sentiment.path("result").path("isError").asBoolean()).isFalse();
        String sentimentText = firstTextContent(sentiment);
        assertThat(sentimentText).contains("\"symbol\":\"AAPL\"").contains("\"sentiment\"").contains("\"label\"");

        // 9. validation failure: missing required symbol -> an MCP error is indicated
        JsonNode missing = postJson("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call",
                 "params":{"name":"get_stock_price","arguments":{}}}""");
        assertThat(isErrorIndicated(missing)).isTrue();

        // 10. invalid input: malformed symbol -> an MCP error is indicated
        JsonNode invalid = postJson("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call",
                 "params":{"name":"get_stock_price","arguments":{"symbol":"BAD!SYMBOL"}}}""");
        assertThat(isErrorIndicated(invalid)).isTrue();

        // 11. provider failure via the stub's reserved FAIL symbol -> an MCP error is indicated
        JsonNode failed = postJson("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call",
                 "params":{"name":"get_stock_price","arguments":{"symbol":"FAIL"}}}""");
        assertThat(isErrorIndicated(failed)).isTrue();
    }

    private boolean isErrorIndicated(JsonNode response) {
        return response != null
                && (response.hasNonNull("error") || response.path("result").path("isError").asBoolean());
    }

    private JsonNode postJson(String body) throws Exception {
        ResponseEntity<String> entity = client.post()
                .uri(MCP_ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> {
                    headers.setBearerAuth(accessToken);
                    if (sessionId != null) {
                        headers.set("Mcp-Session-Id", sessionId);
                    }
                })
                .body(body)
                .retrieve()
                .onStatus(status -> status.value() >= 400, (request, response) -> {
                    // keep the body - MCP errors are still JSON-RPC frames we want to parse
                })
                .toEntity(String.class);

        String session = entity.getHeaders().getFirst("Mcp-Session-Id");
        if (session != null) {
            sessionId = session;
        }

        String responseBody = entity.getBody();
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        // Defensive: if the server answers with an SSE frame, unwrap the data payload.
        if (responseBody.contains("data:")) {
            for (String line : responseBody.split("\\R")) {
                if (line.startsWith("data:")) {
                    return objectMapper.readTree(line.substring(5).trim());
                }
            }
            throw new IllegalStateException("No data payload in SSE response");
        }
        return objectMapper.readTree(responseBody);
    }

    private String firstTextContent(JsonNode callToolResult) {
        return callToolResult.path("result").path("content").get(0).path("text").asText();
    }
}