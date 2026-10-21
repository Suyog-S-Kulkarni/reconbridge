package com.suyog.reconbridge.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentitySecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsIdentityForAuthorizedUser() throws Exception {
        when(jwtDecoder.decode("valid-token"))
                .thenReturn(token(List.of("IMPORTER")));

        mvc.perform(
                        get("/api/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer valid-token"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.subject").value("tenant-a-user")
                )
                .andExpect(
                        jsonPath("$.tenantId").value(
                                "11111111-1111-4111-8111-111111111111"
                        )
                );
    }

    @Test
    void rejectsAuthenticatedUserWithoutApplicationRole()
            throws Exception {

        when(jwtDecoder.decode("no-role-token"))
                .thenReturn(token(List.of("unrelated-role")));

        mvc.perform(
                        get("/api/me")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer no-role-token"
                                )
                )
                .andExpect(status().isForbidden());
    }

    private Jwt token(List<String> roles) {
        Instant now = Instant.now();

        return Jwt.withTokenValue("decoded-test-token")
                .header("alg", "RS256")
                .subject("tenant-a-user")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .audience(List.of("reconbridge-api"))
                .claim(
                        "tenant_id",
                        "11111111-1111-4111-8111-111111111111"
                )
                .claim(
                        "realm_access",
                        Map.of("roles", roles)
                )
                .build();
    }
}