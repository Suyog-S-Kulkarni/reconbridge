package com.suyog.reconbridge.application;

import com.suyog.reconbridge.domain.model.SourceType;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BankReconciliationPreviewIntegrationTest {

    private static final String ENDPOINT =
            "/api/reconciliation/bank-preview";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private JwtDecoder decoder;

    private UUID tenantA;
    private UUID tenantB;

    private UUID settlementA;
    private UUID bankA;

    private UUID settlementB;
    private UUID bankB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        createTenant(tenantA, "Preview tenant A");
        createTenant(tenantB, "Preview tenant B");

        settlementA = createImport(
                tenantA,
                SourceType.PROCESSOR_SETTLEMENT,
                "READY",
                5
        );

        bankA = createImport(
                tenantA,
                SourceType.BANK_STATEMENT,
                "READY",
                6
        );

        settlementB = createImport(
                tenantB,
                SourceType.PROCESSOR_SETTLEMENT,
                "READY",
                1
        );

        bankB = createImport(
                tenantB,
                SourceType.BANK_STATEMENT,
                "READY",
                1
        );

        /*
         * Tenant A: all five settlement outcomes, plus one orphan.
         */
        settlement(tenantA, settlementA, 1, "MATCHED", 1000);
        settlement(tenantA, settlementA, 2, "AMOUNT", 1000);
        settlement(tenantA, settlementA, 3, "CURRENCY", 1000);
        settlement(tenantA, settlementA, 4, "AMBIGUOUS", 1000);
        settlement(tenantA, settlementA, 5, "MISSING", 1000);

        credit(tenantA, bankA, 101, "MATCHED", 1000, "INR");
        credit(tenantA, bankA, 102, "AMOUNT", 900, "INR");
        credit(tenantA, bankA, 103, "CURRENCY", 1000, "USD");
        credit(tenantA, bankA, 104, "AMBIGUOUS", 1000, "INR");
        credit(tenantA, bankA, 105, "AMBIGUOUS", 1000, "INR");
        credit(tenantA, bankA, 106, "ORPHAN", 700, "INR");

        /*
         * Tenant B deliberately reuses batch, line, and credit IDs
         * found in Tenant A's evidence.
         *
         * Isolation must come from tenant/import predicates,
         * not from assuming every evidence ID is globally unique.
         */
        settlement(tenantB, settlementB, 1, "MATCHED", 5000);
        credit(tenantB, bankB, 101, "MATCHED", 5000, "INR");

        token("a", tenantA, List.of("IMPORTER"));
        token("b", tenantB, List.of("IMPORTER"));
        token("auditor", tenantA, List.of("AUDITOR"));
        token("no-role", tenantA, List.of());
    }

    @AfterEach
    void cleanUp() {
        if (tenantA == null || tenantB == null) {
            return;
        }

        /*
         * These are fixed test-owned table names.
         * Only records for this test's random tenant IDs are deleted.
         */
        for (String table : List.of(
                "bank_credit",
                "settlement_line",
                "settlement_batch",
                "import_batch",
                "tenant"
        )) {
            String column = table.equals("tenant")
                    ? "id"
                    : "tenant_id";

            jdbc.update(
                    "DELETE FROM " + table
                            + " WHERE " + column + " IN (?, ?)",
                    tenantA.toString(),
                    tenantB.toString()
            );
        }
    }

    @Test
    void returnsAllMatchingOutcomesAndOrphanCredits() throws Exception {
        preview("a", settlementA, bankA)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.settlementImportId")
                                .value(settlementA.toString())
                )
                .andExpect(
                        jsonPath("$.bankImportId")
                                .value(bankA.toString())
                )
                .andExpect(jsonPath("$.settlementBatchCount").value(5))
                .andExpect(jsonPath("$.bankCreditCount").value(6))
                .andExpect(jsonPath("$.matchedBatchCount").value(1))
                .andExpect(jsonPath("$.unresolvedBatchCount").value(4))
                .andExpect(jsonPath("$.orphanBankCreditCount").value(1))
                .andExpect(jsonPath("$.fullyReconciled").value(false))
                .andExpect(jsonPath("$.results", hasSize(5)))
                .andExpect(
                        jsonPath(
                                "$.results[*].outcome",
                                containsInAnyOrder(
                                        "MATCHED",
                                        "AMOUNT_MISMATCH",
                                        "CURRENCY_MISMATCH",
                                        "AMBIGUOUS",
                                        "MISSING_COUNTERPART"
                                )
                        )
                )
                /*
                 * Batch IDs are generated in ascending numeric order,
                 * matching the repository's ORDER BY batch_id.
                 */
                .andExpect(
                        jsonPath("$.results[0].differenceMinor").value(0)
                )
                .andExpect(
                        jsonPath("$.results[1].differenceMinor").value(100)
                )
                .andExpect(
                        jsonPath("$.results[1].differenceCurrency").value("INR")
                )
                .andExpect(
                        jsonPath("$.results[2].differenceMinor").isEmpty()
                )
                .andExpect(
                        jsonPath(
                                "$.results[3].candidateBankCreditIds",
                                hasSize(2)
                        )
                )
                .andExpect(
                        jsonPath("$.results[3].differenceMinor").isEmpty()
                )
                .andExpect(
                        jsonPath(
                                "$.results[4].candidateBankCreditIds",
                                hasSize(0)
                        )
                )
                .andExpect(
                        jsonPath("$.orphanBankCredits", hasSize(1))
                )
                .andExpect(
                        jsonPath(
                                "$.orphanBankCredits[0].settlementReference"
                        ).value("ORPHAN")
                )
                .andExpect(
                        jsonPath("$.orphanBankCredits[0].amountMinor")
                                .value(700)
                );

        /*
         * Preview must not advance either import's lifecycle/version.
         */
        assertEquals(
                2L,
                importVersion(tenantA, settlementA)
        );

        assertEquals(
                2L,
                importVersion(tenantA, bankA)
        );

        assertEquals(
                "READY",
                importStatus(tenantA, settlementA)
        );

        assertEquals(
                "READY",
                importStatus(tenantA, bankA)
        );
    }

    @Test
    void tenantBSeesOnlyItsOwnEvidenceDespiteReusedIds() throws Exception {
        preview("b", settlementB, bankB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementBatchCount").value(1))
                .andExpect(jsonPath("$.bankCreditCount").value(1))
                .andExpect(jsonPath("$.matchedBatchCount").value(1))
                .andExpect(jsonPath("$.unresolvedBatchCount").value(0))
                .andExpect(jsonPath("$.orphanBankCreditCount").value(0))
                .andExpect(jsonPath("$.fullyReconciled").value(true))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(
                        jsonPath("$.results[0].differenceMinor").value(0)
                );
    }

    @Test
    void rejectsMixedTenantImportPairsInBothDirections() throws Exception {
        preview("a", settlementA, bankB)
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.code").value("IMPORT_NOT_FOUND")
                );

        preview("a", settlementB, bankA)
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.code").value("IMPORT_NOT_FOUND")
                );
    }

    @Test
    void tenantBCannotPreviewTenantAImports() throws Exception {
        preview("b", settlementA, bankA)
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.code").value("IMPORT_NOT_FOUND")
                );
    }

    @Test
    void auditorCanReadPreview() throws Exception {
        preview("auditor", settlementA, bankA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementBatchCount").value(5));
    }

    @Test
    void authenticatedUserWithoutApplicationRoleIsForbidden()
            throws Exception {
        preview("no-role", settlementA, bankA)
                .andExpect(status().isForbidden());
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(
                get(ENDPOINT)
                        .param(
                                "settlementImportId",
                                settlementA.toString()
                        )
                        .param(
                                "bankImportId",
                                bankA.toString()
                        )
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsSwappedSourceTypes() throws Exception {
        preview("a", bankA, settlementA)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code").value("INVALID_IMPORT_SOURCE")
                );
    }

    @Test
    void rejectsImportThatIsNotReady() throws Exception {
        UUID receivedBank = createImport(
                tenantA,
                SourceType.BANK_STATEMENT,
                "RECEIVED",
                0
        );

        preview("a", settlementA, receivedBank)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code").value("IMPORT_NOT_READY")
                );
    }

    @Test
    void malformedImportIdReturnsBadRequest() throws Exception {
        mvc.perform(
                        get(ENDPOINT)
                                .header("Authorization", "Bearer a")
                                .param("settlementImportId", "not-a-uuid")
                                .param("bankImportId", bankA.toString())
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("INVALID_PREVIEW_PARAMETERS")
                );
    }

    @Test
    void missingImportParameterReturnsBadRequest() throws Exception {
        mvc.perform(
                        get(ENDPOINT)
                                .header("Authorization", "Bearer a")
                                .param(
                                        "settlementImportId",
                                        settlementA.toString()
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.code")
                                .value("INVALID_PREVIEW_PARAMETERS")
                );
    }

    @Test
    void readyImportWithoutEvidenceReturnsConflict() throws Exception {
        /*
         * Deliberately create an inconsistent fixture to check
         * the guard against missing persisted evidence.
         */
        UUID emptyBank = createImport(
                tenantA,
                SourceType.BANK_STATEMENT,
                "READY",
                1
        );

        preview("a", settlementA, emptyBank)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code").value("EVIDENCE_MISSING")
                );
    }

    private ResultActions preview(
            String token,
            UUID settlementImportId,
            UUID bankImportId
    ) throws Exception {
        return mvc.perform(
                get(ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .param(
                                "settlementImportId",
                                settlementImportId.toString()
                        )
                        .param(
                                "bankImportId",
                                bankImportId.toString()
                        )
        );
    }

    private void createTenant(UUID id, String name) {
        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                id.toString(),
                name
        );
    }

    private UUID createImport(
            UUID tenantId,
            SourceType sourceType,
            String status,
            long rowCount
    ) {
        UUID importId = UUID.randomUUID();

        String hash = (
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "")
        );

        long version = status.equals("READY") ? 2L : 0L;

        /*
         * Direct inserts are test fixtures for a read-only feature.
         * Production imports must still use the lifecycle services.
         */
        jdbc.update(
                """
                INSERT INTO import_batch (
                    id,
                    tenant_id,
                    source_type,
                    source_system,
                    file_name,
                    file_uri,
                    file_hash,
                    status,
                    business_date,
                    row_count,
                    version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                importId.toString(),
                tenantId.toString(),
                sourceType.name(),
                sourceType == SourceType.BANK_STATEMENT
                        ? "BANK_A"
                        : "PROCESSOR_A",
                "preview-fixture.csv",
                "test://" + importId,
                hash,
                status,
                java.sql.Date.valueOf("2026-10-23"),
                rowCount,
                version
        );

        return importId;
    }

    private void settlement(
            UUID tenantId,
            UUID importId,
            long number,
            String reference,
            long netMinor
    ) {
        UUID batchId = new UUID(0L, number);
        UUID lineId = new UUID(0L, number + 1000L);

        jdbc.update(
                """
                INSERT INTO settlement_batch (
                    tenant_id,
                    import_id,
                    batch_id,
                    provider,
                    settlement_reference,
                    bank_account_reference,
                    declared_net_minor,
                    currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                importId.toString(),
                batchId.toString(),
                "PROCESSOR_A",
                reference,
                "BANK-001",
                netMinor,
                "INR"
        );

        jdbc.update(
                """
                INSERT INTO settlement_line (
                    tenant_id,
                    import_id,
                    line_id,
                    batch_id,
                    payment_reference,
                    gross_minor,
                    fee_minor,
                    net_minor
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                importId.toString(),
                lineId.toString(),
                batchId.toString(),
                "PAY-" + number,
                netMinor + 10L,
                10L,
                netMinor
        );
    }

    private void credit(
            UUID tenantId,
            UUID importId,
            long number,
            String reference,
            long amountMinor,
            String currency
    ) {
        jdbc.update(
                """
                INSERT INTO bank_credit (
                    tenant_id,
                    import_id,
                    credit_id,
                    provider,
                    bank_account_reference,
                    settlement_reference,
                    amount_minor,
                    currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                importId.toString(),
                new UUID(0L, number).toString(),
                "PROCESSOR_A",
                "BANK-001",
                reference,
                amountMinor,
                currency
        );
    }

    private long importVersion(UUID tenantId, UUID importId) {
        Long version = jdbc.queryForObject(
                """
                SELECT version
                FROM import_batch
                WHERE tenant_id = ? AND id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        if (version == null) {
            throw new AssertionError("Import version was not found.");
        }

        return version;
    }

    private String importStatus(UUID tenantId, UUID importId) {
        return jdbc.queryForObject(
                """
                SELECT status
                FROM import_batch
                WHERE tenant_id = ? AND id = ?
                """,
                String.class,
                tenantId.toString(),
                importId.toString()
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
                .claim(
                        "realm_access",
                        Map.of("roles", roles)
                )
                .build();

        when(decoder.decode(value)).thenReturn(jwt);
    }
}