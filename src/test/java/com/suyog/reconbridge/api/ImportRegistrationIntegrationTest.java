package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ImportRegistrationService;
import com.suyog.reconbridge.application.exception.DuplicateImportException;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportRegistrationIntegrationTest {

    private static final String FILE_HASH = "a".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ImportRegistrationService registrationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantA, "Registration Tenant A")
        );

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantB, "Registration Tenant B")
        );
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM import_batch WHERE tenant_id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );

        jdbcTemplate.update(
                "DELETE FROM tenant WHERE id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );
    }

    @Test
    void importerCreatesImportAndCanRetrieveIt() throws Exception {
        mockToken("importer-a", tenantA, List.of("IMPORTER"));

        String location = register("importer-a", validBody())
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        assertThat(location).startsWith("/api/imports/");

        mockMvc.perform(get(location)
                        .header("Authorization", "Bearer importer-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$.sourceType")
                        .value("PROCESSOR_SETTLEMENT"));

        assertThat(importCount(tenantA)).isEqualTo(1);
    }

    @Test
    void duplicateReturnsConflictAndLaterRegistrationStillWorks()
            throws Exception {
        mockToken("importer-a", tenantA, List.of("IMPORTER"));

        register("importer-a", validBody())
                .andExpect(status().isCreated());

        register("importer-a", validBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_IMPORT"));

        assertThat(importCount(tenantA)).isEqualTo(1);

        String differentContent =
                validBody().replace(FILE_HASH, "b".repeat(64));

        register("importer-a", differentContent)
                .andExpect(status().isCreated());

        assertThat(importCount(tenantA)).isEqualTo(2);
    }

    @Test
    void differentTenantsCanRegisterIdenticalContent() throws Exception {
        mockToken("importer-a", tenantA, List.of("IMPORTER"));
        mockToken("importer-b", tenantB, List.of("IMPORTER"));

        register("importer-a", validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantA.toString()));

        mockMvc.perform(post("/api/imports")
                        .header("Authorization", "Bearer importer-b")
                        .header("X-Tenant-ID", tenantA.toString())
                        .param("tenantId", tenantA.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantB.toString()));

        assertThat(importCount(tenantA)).isEqualTo(1);
        assertThat(importCount(tenantB)).isEqualTo(1);
    }

    @Test
    void auditorCannotRegisterImport() throws Exception {
        mockToken("auditor-a", tenantA, List.of("AUDITOR"));

        register("auditor-a", validBody())
                .andExpect(status().isForbidden());

        assertThat(importCount(tenantA)).isZero();
    }

    @Test
    void registrationRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/imports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        assertThat(importCount(tenantA)).isZero();
    }

    @Test
    void rejectsInvalidHash() throws Exception {
        mockToken("importer-a", tenantA, List.of("IMPORTER"));

        register("importer-a", validBody().replace(FILE_HASH, "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(importCount(tenantA)).isZero();
    }

    @Test
    void rejectsBlankSourceSystem() throws Exception {
        mockToken("importer-a", tenantA, List.of("IMPORTER"));

        register("importer-a", validBody().replace("TEST_PROCESSOR", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(importCount(tenantA)).isZero();
    }

    @Test
    void rejectsUnsupportedSourceType() throws Exception {
        mockToken("importer-a", tenantA, List.of("IMPORTER"));

        String invalidBody = validBody().replace(
                "PROCESSOR_SETTLEMENT",
                "UNSUPPORTED_SOURCE"
        );

        register("importer-a", invalidBody)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        assertThat(importCount(tenantA)).isZero();
    }

    @Test
    void rejectsUnprovisionedTenant() throws Exception {
        UUID unknownTenant = UUID.randomUUID();

        mockToken("unknown-tenant", unknownTenant, List.of("IMPORTER"));

        register("unknown-tenant", validBody())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("TENANT_NOT_PROVISIONED"));

        assertThat(importCount(unknownTenant)).isZero();
    }

    @Test
    void concurrentRegistrationsPersistOnlyOneImport() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<String> first = executor.submit(
                    () -> concurrentAttempt(workersReady, start)
            );

            Future<String> second = executor.submit(
                    () -> concurrentAttempt(workersReady, start)
            );

            assertThat(workersReady.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            String firstResult = first.get(30, TimeUnit.SECONDS);
            String secondResult = second.get(30, TimeUnit.SECONDS);

            assertThat(List.of(firstResult, secondResult))
                    .containsExactlyInAnyOrder("CREATED", "DUPLICATE");

            assertThat(importCount(tenantA)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Registration test workers did not stop."
                );
            }
        }
    }

    private String concurrentAttempt(
            CountDownLatch workersReady,
            CountDownLatch start
    ) throws InterruptedException {
        workersReady.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Start signal timed out.");
        }

        try {
            registrationService.register(
                    tenantA,
                    SourceType.PROCESSOR_SETTLEMENT,
                    "TEST_PROCESSOR",
                    "settlement.csv",
                    "test://imports/settlement.csv",
                    FILE_HASH,
                    LocalDate.of(2026, 10, 21)
            );

            return "CREATED";
        } catch (DuplicateImportException exception) {
            return "DUPLICATE";
        }
    }

    private ResultActions register(
            String token,
            String body
    ) throws Exception {
        return mockMvc.perform(post("/api/imports")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long importCount(UUID tenantId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE tenant_id = ?",
                Long.class,
                tenantId.toString()
        );

        return count == null ? 0 : count;
    }

    private String validBody() {
        return """
                {
                  "sourceType": "PROCESSOR_SETTLEMENT",
                  "sourceSystem": "TEST_PROCESSOR",
                  "fileName": "settlement.csv",
                  "fileUri": "test://imports/settlement.csv",
                  "fileHash": "%s",
                  "businessDate": "2026-10-21"
                }
                """.formatted(FILE_HASH);
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
                .claim("realm_access", Map.of("roles", roles))
                .build();

        when(jwtDecoder.decode(tokenValue)).thenReturn(jwt);
    }
}