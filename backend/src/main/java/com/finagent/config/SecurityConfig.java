package com.finagent.config;

import com.finagent.auth.JwtAuthenticationFilter;
import com.finagent.auth.RateLimitFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Phase 16: application security boundary (stateless JWT, no sessions).
 *
 * <p>Authorization matrix (enforced when {@code finagent.auth.enabled=true},
 * the default for test/postgres/prod):</p>
 * <ul>
 *   <li>PUBLIC: {@code POST /api/v1/auth/register}, {@code POST /api/v1/auth/login},
 *   {@code GET /api/v1/health}, actuator {@code /health} (+{@code /info}), {@code /error},
 *   CORS preflights ({@code OPTIONS}).</li>
 *   <li>AUTHENTICATED (any role): {@code /api/v1/auth/me}, {@code /api/v1/stocks/**},
 *   {@code /api/v1/research/**}, {@code /mcp} (+{@code /mcp/**}).</li>
 *   <li>ADMIN only: {@code /api/v1/admin/**}.</li>
 *   <li>Swagger ({@code /swagger-ui/**}, {@code /v3/api-docs/**}): public when
 *   {@code finagent.swagger.enabled=true}, denied otherwise.</li>
 * </ul>
 *
 * <p>When {@code finagent.auth.enabled=false} (DB-less dev profile — no users can
 * exist) the API stays open exactly like before Phase 16, with a loud startup
 * warning. Hardening notes: CSRF disabled because authentication is Bearer
 * tokens, never cookies (nothing for a forged cross-site request to present);
 * default security headers apply (nosniff, DENY framing, referrer policy);
 * no sessions are ever created.</p>
 */
@Configuration
@Slf4j
public class SecurityConfig {

    private final FinAgentProperties properties;
    private final JwtAuthenticationFilter jwtFilter;
    private final RateLimitFilter rateLimitFilter;
    private final SecurityFailureHandlers.JsonAuthenticationEntryPoint entryPoint;
    private final SecurityFailureHandlers.JsonAccessDeniedHandler deniedHandler;

    public SecurityConfig(FinAgentProperties properties,
                          JwtAuthenticationFilter jwtFilter,
                          // Lenient: absent in the DB-less dev profile (auth disabled).
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          RateLimitFilter rateLimitFilter,
                          SecurityFailureHandlers.JsonAuthenticationEntryPoint entryPoint,
                          SecurityFailureHandlers.JsonAccessDeniedHandler deniedHandler) {
        this.properties = properties;
        this.jwtFilter = jwtFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.entryPoint = entryPoint;
        this.deniedHandler = deniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));

        if (!properties.getAuth().isEnabled()) {
            log.warn("SECURITY: finagent.auth.enabled=false — API authentication is OFF "
                    + "(DB-less dev profile only; never use in production)");
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/health").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                .access((authentication, context) -> {
                    boolean swaggerOn = properties.getSwagger().isEnabled();
                    return new org.springframework.security.authorization.AuthorizationDecision(swaggerOn);
                })
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/mcp", "/mcp/**").authenticated()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll());

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        if (rateLimitFilter != null) {
            http.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
        }
        return http.build();
    }
}
