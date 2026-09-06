package com.finagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Post-project hardening: only {@code health} (+{@code info}) may be reachable
 * under {@code /actuator/**}. Sensitive diagnostics (env, beans, mappings,
 * configprops, dumps, trace) must stay denied — even to authenticated callers —
 * because the security chain ends in {@code denyAll}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class ActuatorExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/env", "/actuator/env/home",
            "/actuator/beans", "/actuator/mappings", "/actuator/configprops",
            "/actuator/heapdump", "/actuator/threaddump", "/actuator/trace", "/actuator/loggers"
    })
    void sensitiveActuatorEndpointsStayDenied(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden());
    }
}
