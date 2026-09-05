package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 16: JWT round-trip, minimal claims, expiry, tamper and wrong-key
 * rejection. No Spring context.
 */
class JwtServiceTest {

    private final JwtService jwtService = new JwtService(new FinAgentProperties());

    @Test
    void createAndParseRoundTrip() {
        UUID id = UUID.randomUUID();
        String token = jwtService.createToken(id, "analyst@example.com", "USER");

        Claims claims = jwtService.parseToken(token);

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("analyst@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void claimsCarryNoSensitiveMaterial() {
        String token = jwtService.createToken(UUID.randomUUID(), "a@example.com", "USER");
        // Raw payload segment must not contain password/API-key material.
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload.toLowerCase()).doesNotContain("password", "secret", "apikey", "api_key");
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        FinAgentProperties props = new FinAgentProperties();
        props.getAuth().setTokenTtl(Duration.ofMillis(1));
        JwtService shortLived = new JwtService(props);
        String token = shortLived.createToken(UUID.randomUUID(), "a@example.com", "USER");
        Thread.sleep(20);

        assertThatThrownBy(() -> shortLived.parseToken(token))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.createToken(UUID.randomUUID(), "a@example.com", "USER");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> jwtService.parseToken(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tokenSignedByAnotherSecretIsRejected() {
        FinAgentProperties other = new FinAgentProperties();
        other.getAuth().setJwtSecret("another-test-only-secret-that-is-long-enough-0123456789");
        String foreign = new JwtService(other).createToken(UUID.randomUUID(), "a@example.com", "USER");

        assertThatThrownBy(() -> jwtService.parseToken(foreign))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void malformedAndBlankTokensAreRejected() {
        assertThatThrownBy(() -> jwtService.parseToken("not-a-jwt"))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> jwtService.parseToken(""))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> jwtService.parseToken(null))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void unsignedTokenIsRejected() {
        // Phase 17: alg=none must never verify — the parser requires our HS256 key.
        String unsigned = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".";

        assertThatThrownBy(() -> jwtService.parseToken(unsigned))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void shortSecretFailsFast() {
        FinAgentProperties props = new FinAgentProperties();
        props.getAuth().setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtService(props)).isInstanceOf(IllegalStateException.class);
    }
}
