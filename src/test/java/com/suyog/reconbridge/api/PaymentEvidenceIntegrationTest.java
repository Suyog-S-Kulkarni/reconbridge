package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ImportLifecycleService;
import com.suyog.reconbridge.application.InternalPaymentPersistenceService;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentEvidenceIntegrationTest {

    private static final UUID PAYMENT_ONE = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );

    private static final UUID PAYMENT_TWO = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private ImportBatchRepository imports;

    @Autowired
    private ImportLifecycleService lifecycle;

    @Autowired
    private InternalPaymentPersistenceService persistence;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder decoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID importA;
    private UUID importB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        tenants.saveAndFlush(
                new TenantEntity(tenantA, "Payment evidence A")
        );

        tenants.saveAndFlush(
                new TenantEntity(tenantB, "Payment evidence B")
        );

        for (String role : List.of(
                "IMPORTER",
                "ANALYST",
                "APPROVER",
                "AUDITOR"
        )) {
            token(role, tenantA, List.of(role));
        }

        token("tenant-b", tenantB, List.of("AUDITOR"));
        token("no-role", tenantA, List.of());

        importA = readyImport(tenantA, "A", 12550);
        importB = readyImport(tenantB, "B", 9900);
    }

    @AfterEach
    void cleanUp() {
        if (tenantA == null || tenantB == null) {
            return;
        }

        jdbc.update(
                "DELETE FROM internal_payment WHERE tenant_id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );

        jdbc.update(
                "DELETE FROM import_batch WHERE tenant_id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );

        jdbc.update(
                "DELETE FROM tenant WHERE id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );
    }

    @Test
    void allApplicationReadRolesCanReadPayments() throws Exception {
        for (String role : List.of(
                "IMPORTER",
                "ANALYST",
                "APPROVER",
                "AUDITOR"
        )) {
            query(role, importA.toString(), "0", "20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andExpect(
                            jsonPath("$.items[0].paymentId")
                                    .value(PAYMENT_ONE.toString())
                    )
                    .andExpect(
                            jsonPath("$.items[0].paymentReference")
                                    .value("A-001")
                    )
                    .andExpect(
                            jsonPath("$.items[0].amountMinor").value(12550)
                    );
        }
    }

    @Test
    void returnsStablePagesAndAnEmptyOutOfRangePage() throws Exception {
        query("AUDITOR", importA.toString(), "0", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(
                        jsonPath("$.items[0].paymentId")
                                .value(PAYMENT_ONE.toString())
                );

        query("AUDITOR", importA.toString(), "1", "1")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].paymentId")
                                .value(PAYMENT_TWO.toString())
                );

        query("AUDITOR", importA.toString(), "2", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void usesDefaultPagination() throws Exception {
        mvc.perform(
                        get("/api/imports/{id}/payments", importA)
                                .header("Authorization", "Bearer AUDITOR")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void isolatesTenantsEvenWhenPaymentIdsAreReused() throws Exception {
        query("tenant-b", importB.toString(), "0", "20")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.items[0].paymentId")
                                .value(PAYMENT_ONE.toString())
                )
                .andExpect(
                        jsonPath("$.items[0].paymentReference").value("B-001")
                )
                .andExpect(
                        jsonPath("$.items[0].amountMinor").value(9900)
                );

        query("tenant-b", importA.toString(), "0", "20")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));

        query("AUDITOR", importB.toString(), "0", "20")
                .andExpect(status().isNotFound());
    }

    @Test
    void missingImportReturnsNotFound() throws Exception {
        query("AUDITOR", UUID.randomUUID().toString(), "0", "20")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));
    }

    @Test
    void rejectsWrongSourceType() throws Exception {
        ImportBatchEntity wrongSource = received(
                tenantA,
                SourceType.BANK_STATEMENT
        );

        query("AUDITOR", wrongSource.getId().toString(), "0", "20")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_SOURCE"));
    }

    @Test
    void rejectsImportThatIsNotReady() throws Exception {
        ImportBatchEntity received = received(
                tenantA,
                SourceType.INTERNAL_PAYMENT
        );

        query("AUDITOR", received.getId().toString(), "0", "20")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_READY"));
    }

    @Test
    void rejectsReadyImportWithMissingEvidence() throws Exception {
        // Deliberately corrupt this isolated fixture to test detection.
        jdbc.update(
                """
                DELETE FROM internal_payment
                WHERE tenant_id = ? AND import_id = ?
                """,
                tenantA.toString(),
                importA.toString()
        );

        query("AUDITOR", importA.toString(), "0", "20")
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("PAYMENT_EVIDENCE_INCONSISTENT")
                );
    }

    @Test
    void rejectsRowCountMismatch() throws Exception {
        jdbc.update(
                """
                UPDATE import_batch
                SET row_count = 3
                WHERE tenant_id = ? AND id = ?
                """,
                tenantA.toString(),
                importA.toString()
        );

        query("AUDITOR", importA.toString(), "0", "20")
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("PAYMENT_EVIDENCE_INCONSISTENT")
                );
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        for (String[] values : List.of(
                new String[]{"-1", "20"},
                new String[]{"0", "0"},
                new String[]{"0", "101"}
        )) {
            query("AUDITOR", importA.toString(), values[0], values[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.code").value("INVALID_PAGINATION")
                    );
        }
    }

    @Test
    void rejectsMalformedParameters() throws Exception {
        query("AUDITOR", "not-a-uuid", "0", "20")
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("INVALID_PAYMENT_QUERY_PARAMETER")
                );

        query("AUDITOR", importA.toString(), "abc", "20")
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("INVALID_PAYMENT_QUERY_PARAMETER")
                );
    }

    @Test
    void rejectsMissingTokenAndMissingRole() throws Exception {
        mvc.perform(
                get("/api/imports/{id}/payments", importA)
        ).andExpect(status().isUnauthorized());

        query("no-role", importA.toString(), "0", "20")
                .andExpect(status().isForbidden());
    }

    @Test
    void readingDoesNotAdvanceImportVersion() throws Exception {
        ImportBatchSnapshot before = lifecycle.get(tenantA, importA);

        query("AUDITOR", importA.toString(), "0", "20")
                .andExpect(status().isOk());

        assertEquals(before, lifecycle.get(tenantA, importA));
    }

    private ResultActions query(
            String token,
            String importId,
            String page,
            String size
    ) throws Exception {
        return mvc.perform(
                get("/api/imports/{id}/payments", importId)
                        .header("Authorization", "Bearer " + token)
                        .param("page", page)
                        .param("size", size)
        );
    }

    private UUID readyImport(
            UUID tenantId,
            String prefix,
            long firstAmount
    ) {
        ImportBatchEntity received = received(
                tenantId,
                SourceType.INTERNAL_PAYMENT
        );

        ImportBatchSnapshot processing = lifecycle.startProcessing(
                tenantId,
                received.getId(),
                received.getVersion()
        );

        persistence.complete(
                tenantId,
                received.getId(),
                processing.version(),
                List.of(
                        payment(
                                tenantId,
                                PAYMENT_TWO,
                                prefix + "-002",
                                25000
                        ),
                        payment(
                                tenantId,
                                PAYMENT_ONE,
                                prefix + "-001",
                                firstAmount
                        )
                )
        );

        return received.getId();
    }

    private ImportBatchEntity received(
            UUID tenantId,
            SourceType sourceType
    ) {
        String hash = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .repeat(2);

        return imports.saveAndFlush(
                new ImportBatchEntity(
                        tenantId,
                        sourceType,
                        "PROCESSOR_A",
                        "payments.csv",
                        "local:" + UUID.randomUUID() + ".csv",
                        hash,
                        LocalDate.of(2026, 11, 23)
                )
        );
    }

    private PaymentRecord payment(
            UUID tenantId,
            UUID paymentId,
            String reference,
            long amount
    ) {
        return new PaymentRecord(
                paymentId,
                new MatchKey(
                        tenantId,
                        "PROCESSOR_A",
                        reference
                ),
                Money.ofMinor(amount, "INR")
        );
    }

    private void token(
            String value,
            UUID tenantId,
            List<String> roles
    ) {
        Instant now = Instant.now();

        Jwt jwt = Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .issuer("http://localhost:8081/realms/reconbridge")
                .audience(List.of("reconbridge-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("tenant_id", tenantId.toString())
                .claim("realm_access", Map.of("roles", roles))
                .build();

        when(decoder.decode(value)).thenReturn(jwt);
    }
}