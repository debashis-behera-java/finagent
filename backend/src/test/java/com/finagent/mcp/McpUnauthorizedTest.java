package com.finagent.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: the MCP endpoint is NOT anonymously reachable — calls without a
 * Bearer token are rejected with 401 JSON (never tool output).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpUnauthorizedTest {

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void anonymousInitializeIsRejected() {
        ResponseEntity<String> entity = client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                          "protocolVersion":"2025-06-18","capabilities":{},
                          "clientInfo":{"name":"anon","version":"1.0"}}}""")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    // expected — captured below
                })
                .toEntity(String.class);

        assertThat(entity.getStatusCode().value()).isEqualTo(401);
        assertThat(entity.getBody()).contains("\"status\":401");
    }

    @Test
    void bogusTokenIsRejected() {
        ResponseEntity<String> entity = client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> headers.setBearerAuth("bogus.token.value"))
                .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    // expected — captured below
                })
                .toEntity(String.class);

        assertThat(entity.getStatusCode().value()).isEqualTo(401);
    }
}
