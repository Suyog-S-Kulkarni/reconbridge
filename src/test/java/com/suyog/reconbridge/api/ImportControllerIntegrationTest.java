package com.suyog.reconbridge.api;

import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImportControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ImportBatchRepository importBatchRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID importId;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantA, "Import API Tenant A")
        );

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantB, "Import API Tenant B")
        );

        ImportBatchEntity saved = importBatchRepository.saveAndFlush(
                createImport(tenantA)
        );

        importId = saved.getId();
    }

    private ImportBatchEntity createImport(UUID tenantId) {
        return new ImportBatchEntity(
                tenantId,
                SourceType.PROCESSOR_SETTLEMENT,
                "TEST_PROCESSOR",
                "settlement-test.csv",
                "test://imports/settlement-test.csv",
                "a".repeat(64),
                LocalDate.of(2026, 10, 21)
        );
    }

    @Test
    void returnsImportForItsTenant() throws Exception {
        mockToken(
                "tenant-a-token",
                tenantA,
                List.of("IMPORTER")
        );

        mockMvc.perform(
                        get("/api/imports/{importId}", importId)
                                .header(
                                        "Authorization",
                                        "Bearer tenant-a-token"
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.id")
                                .value(importId.toString())
                )
                .andExpect(
                        jsonPath("$.tenantId")
                                .value(tenantA.toString())
                )
                .andExpect(
                        jsonPath("$.sourceType")
                                .value("PROCESSOR_SETTLEMENT")
                )
                .andExpect(
                        jsonPath("$.sourceSystem")
                                .value("TEST_PROCESSOR")
                )
                .andExpect(
                        jsonPath("$.status")
                                .value("RECEIVED")
                )
                .andExpect(
                        jsonPath("$.rowCount")
                                .value(0)
                )
                .andExpect(
                        jsonPath("$.businessDate")
                                .value("2026-10-21")
                )
                .andExpect(
                        jsonPath("$.version")
                                .value(0)
                );
    }

    @Test
    void anotherTenantCannotOverrideIdentityUsingRequestValues()
            throws Exception {

        mockToken(
                "tenant-b-token",
                tenantB,
                List.of("IMPORTER")
        );

        mockMvc.perform(
                        get("/api/imports/{importId}", importId)
                                .header(
                                        "Authorization",
                                        "Bearer tenant-b-token"
                                )
                                .header(
                                        "X-Tenant-ID",
                                        tenantA.toString()
                                )
                                .param(
                                        "tenantId",
                                        tenantA.toString()
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.code")
                                .value("IMPORT_NOT_FOUND")
                );
    }

    @Test
    void returnsNotFoundForUnknownImport() throws Exception {
        mockToken(
                "tenant-a-token",
                tenantA,
                List.of("AUDITOR")
        );

        mockMvc.perform(
                        get(
                                "/api/imports/{importId}",
                                UUID.randomUUID()
                        )
                                .header(
                                        "Authorization",
                                        "Bearer tenant-a-token"
                                )
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.code")
                                .value("IMPORT_NOT_FOUND")
                );
    }

    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(
                        get("/api/imports/{importId}", importId)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWithoutApplicationRole() throws Exception {
        mockToken(
                "no-role-token",
                tenantA,
                List.of()
        );

        mockMvc.perform(
                        get("/api/imports/{importId}", importId)
                                .header(
                                        "Authorization",
                                        "Bearer no-role-token"
                                )
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMalformedImportId() throws Exception {
        mockToken(
                "tenant-a-token",
                tenantA,
                List.of("ANALYST")
        );

        mockMvc.perform(
                        get("/api/imports/not-a-uuid")
                                .header(
                                        "Authorization",
                                        "Bearer tenant-a-token"
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("INVALID_IMPORT_ID")
                );
    }

    private void mockToken(
            String tokenValue,
            UUID tenantId,
            List<String> roles
    ) {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuer("http://localhost:8081/realms/reconbridge")
                .audience(List.of("reconbridge-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("tenant_id", tenantId.toString())
                .claim(
                        "realm_access",
                        Map.of("roles", roles)
                )
                .build();

        when(jwtDecoder.decode(tokenValue)).thenReturn(jwt);
    }
}