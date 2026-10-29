package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
class ReconciliationQueryIntegrationTest {

    private static final String ROOT = "/api/reconciliation/runs";

    private static final UUID RUN_ONE = UUID.fromString(
            "00000000-0000-4000-8000-000000000001"
    );
    private static final UUID RUN_TWO = UUID.fromString(
            "00000000-0000-4000-8000-000000000002"
    );
    private static final UUID RUN_THREE = UUID.fromString(
            "00000000-0000-4000-8000-000000000003"
    );
    private static final UUID FOREIGN_RUN = UUID.fromString(
            "00000000-0000-4000-8000-000000000009"
    );

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationRunRepository runs;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private JwtDecoder decoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID orphanId;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        orphanId = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Query tenant A"
        );

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Query tenant B"
        );

        seedRun(tenantA, RUN_ONE, "tenant-a-creator");
        seedRun(tenantA, RUN_TWO, "tenant-a-creator");
        seedRun(tenantA, RUN_THREE, "tenant-a-creator");
        seedRun(tenantB, FOREIGN_RUN, "tenant-b-creator");

        for (String role : List.of(
                "IMPORTER", "ANALYST", "APPROVER", "AUDITOR"
        )) {
            token(role, tenantA, List.of(role));
        }

        token("foreign", tenantB, List.of("AUDITOR"));
        token("no-role", tenantA, List.of());
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of(
                "investigation_note",
                "exception_case",
                "reconciliation_run_request",
                "reconciliation_run_item",
                "reconciliation_run",
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
    void historyUsesStableOrderingAndRealPages() throws Exception {
        authorized(ROOT + "?page=0&size=2", "AUDITOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id")
                        .value(RUN_THREE.toString()))
                .andExpect(jsonPath("$.items[1].id")
                        .value(RUN_TWO.toString()));

        authorized(ROOT + "?page=1&size=2", "AUDITOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id")
                        .value(RUN_ONE.toString()));

        authorized(ROOT + "?page=2&size=2", "AUDITOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void allApplicationReadRolesCanUseBothEndpoints() throws Exception {
        for (String role : List.of(
                "IMPORTER", "ANALYST", "APPROVER", "AUDITOR"
        )) {
            authorized(ROOT, role)
                    .andExpect(status().isOk());

            authorized(unresolved(RUN_ONE), role)
                    .andExpect(status().isOk());
        }
    }

    @Test
    void unresolvedResultsExcludeMatchesAndIncludeOrphans()
            throws Exception {
        authorized(
                unresolved(RUN_ONE) + "?page=0&size=10",
                "AUDITOR"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(
                        jsonPath(
                                "$.items[?(@.outcome == 'MATCHED')]"
                        ).isEmpty()
                )
                .andExpect(jsonPath("$.items[4].itemType")
                        .value("ORPHAN"))
                .andExpect(jsonPath("$.items[4].evidenceId")
                        .value(orphanId.toString()))
                .andExpect(jsonPath(
                        "$.items[4].orphanCredit.amountMinor"
                ).value(500));

        authorized(
                unresolved(RUN_ONE) + "?page=1&size=2",
                "AUDITOR"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPages").value(3));

        authorized(
                unresolved(RUN_ONE) + "?page=99&size=2",
                "AUDITOR"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void filtersAmountMismatchUsingStoredPayload() throws Exception {
        authorized(
                unresolved(RUN_ONE)
                        + "?outcome=AMOUNT_MISMATCH",
                "ANALYST"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].itemType").value("BATCH"))
                .andExpect(jsonPath("$.items[0].outcome")
                        .value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath(
                        "$.items[0].batchResult.differenceMinor"
                ).value(100));
    }

    @Test
    void filtersOrphanCredits() throws Exception {
        authorized(
                unresolved(RUN_ONE)
                        + "?outcome=ORPHAN_BANK_CREDIT",
                "AUDITOR"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].itemType").value("ORPHAN"))
                .andExpect(jsonPath("$.items[0].evidenceId")
                        .value(orphanId.toString()));
    }

    @Test
    void tenantBOnlySeesItsHistoryAndCannotReadTenantARun()
            throws Exception {
        authorized(ROOT, "foreign")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id")
                        .value(FOREIGN_RUN.toString()))
                .andExpect(jsonPath("$.items[0].createdBy")
                        .value("tenant-b-creator"));

        authorized(unresolved(RUN_ONE), "foreign")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void missingRunReturnsNotFound() throws Exception {
        authorized(unresolved(UUID.randomUUID()), "AUDITOR")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test
    void missingTokenReturnsUnauthorized() throws Exception {
        mvc.perform(get(ROOT))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(unresolved(RUN_ONE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingApplicationRoleReturnsForbidden() throws Exception {
        authorized(ROOT, "no-role")
                .andExpect(status().isForbidden());

        authorized(unresolved(RUN_ONE), "no-role")
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        for (String query : List.of(
                "?page=-1",
                "?size=0",
                "?size=101"
        )) {
            authorized(ROOT + query, "AUDITOR")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_PAGINATION"));

            authorized(unresolved(RUN_ONE) + query, "AUDITOR")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_PAGINATION"));
        }

        authorized(ROOT + "?page=abc", "AUDITOR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_QUERY_PARAMETER"));
    }

    @Test
    void rejectsUnsupportedOutcomeFilter() throws Exception {
        for (String outcome : List.of("MATCHED", "UNKNOWN")) {
            authorized(
                    unresolved(RUN_ONE) + "?outcome=" + outcome,
                    "AUDITOR"
            )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_QUERY_PARAMETER"));
        }
    }

    @Test
    void rejectsMalformedRunId() throws Exception {
        authorized(
                ROOT + "/not-a-uuid/unresolved-results",
                "AUDITOR"
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_QUERY_PARAMETER"));
    }

    private ResultActions authorized(
            String url,
            String token
    ) throws Exception {
        return mvc.perform(
                get(url).header(
                        "Authorization",
                        "Bearer " + token
                )
        );
    }

    private String unresolved(UUID runId) {
        return ROOT + "/" + runId + "/unresolved-results";
    }

    private void seedRun(
            UUID tenant,
            UUID runId,
            String creator
    ) {
        UUID settlement = imported(tenant, "PROCESSOR_SETTLEMENT");
        UUID bank = imported(tenant, "BANK_STATEMENT");

        BankReconciliationPreview report =
                new BankReconciliationPreview(
                        settlement,
                        bank,
                        5L,
                        6L,
                        1L,
                        4L,
                        1L,
                        false,
                        List.of(
                                batch(MatchOutcome.MATCHED),
                                batch(MatchOutcome.MISSING_COUNTERPART),
                                batch(MatchOutcome.AMBIGUOUS),
                                batch(MatchOutcome.AMOUNT_MISMATCH),
                                batch(MatchOutcome.CURRENCY_MISMATCH)
                        ),
                        List.of(
                                new BankReconciliationPreview.OrphanCredit(
                                        orphanId,
                                        "PROCESSOR_A",
                                        "BANK-001",
                                        "ORPHAN-001",
                                        500L,
                                        "INR"
                                )
                        )
                );

        StoredReconciliationRun run = new StoredReconciliationRun(
                runId,
                "EXACT_SETTLEMENT_BANK_V1",
                1,
                creator,
                Instant.parse("2026-10-29T10:00:00Z"),
                report
        );

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status -> runs.save(tenant, run)
                );
    }

    private BankReconciliationPreview.BatchResult batch(
            MatchOutcome outcome
    ) {
        List<UUID> candidates = switch (outcome) {
            case MISSING_COUNTERPART -> List.of();
            case AMBIGUOUS -> List.of(
                    UUID.randomUUID(),
                    UUID.randomUUID()
            );
            default -> List.of(UUID.randomUUID());
        };

        Long difference = switch (outcome) {
            case MATCHED -> 0L;
            case AMOUNT_MISMATCH -> 100L;
            default -> null;
        };

        return new BankReconciliationPreview.BatchResult(
                UUID.randomUUID(),
                "PROCESSOR_A",
                "BANK-001",
                "SET-" + outcome.name(),
                outcome,
                candidates,
                difference,
                difference == null ? null : "INR"
        );
    }

    private UUID imported(UUID tenant, String source) {
        UUID id = UUID.randomUUID();

        String hash = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");

        jdbc.update(
                """
                INSERT INTO import_batch (
                    id, tenant_id, source_type, source_system,
                    file_name, file_uri, file_hash, status,
                    business_date, row_count, version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.toString(),
                tenant.toString(),
                source,
                "QUERY_TEST_SOURCE",
                "query-fixture.csv",
                "test://" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-10-29"),
                source.equals("BANK_STATEMENT") ? 6L : 5L,
                2L
        );

        return id;
    }

    private void token(
            String value,
            UUID tenant,
            List<String> roles
    ) {
        Instant now = Instant.now();

        when(decoder.decode(value)).thenReturn(
                Jwt.withTokenValue(value)
                        .header("alg", "RS256")
                        .subject("query-test-user")
                        .issuer(
                                "http://localhost:8081/realms/reconbridge"
                        )
                        .audience(List.of("reconbridge-api"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claim("tenant_id", tenant.toString())
                        .claim(
                                "realm_access",
                                Map.of("roles", roles)
                        )
                        .build()
        );
    }
}