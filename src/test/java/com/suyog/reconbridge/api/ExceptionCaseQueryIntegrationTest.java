package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ExceptionCaseService;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.application.model.StoredReconciliationRun;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.persistence.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExceptionCaseQueryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExceptionCaseService caseService;

    @Autowired
    private ReconciliationRunRepository runRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantA;
    private UUID tenantB;

    private List<UUID> casesA;
    private UUID caseB;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Case query tenant A"
        );

        jdbcTemplate.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Case query tenant B"
        );

        casesA = createCases(tenantA, 3);
        caseB = createCases(tenantB, 1).getFirst();

        caseService.claim(
                tenantA,
                casesA.get(0),
                0,
                "analyst-one"
        );

        caseService.claim(
                tenantA,
                casesA.get(1),
                0,
                "analyst-two"
        );

        // Force equal creation timestamps to exercise the UUID tie-breaker.
        jdbcTemplate.update(
                """
                UPDATE exception_case
                SET created_at_utc = ?
                WHERE tenant_id = ?
                """,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                tenantA.toString()
        );

        token("analyst", tenantA, "analyst-one", "ANALYST");
        token("analyst-two", tenantA, "analyst-two", "ANALYST");
        token("importer", tenantA, "importer-one", "IMPORTER");
        token("approver", tenantA, "approver-one", "APPROVER");
        token("auditor", tenantA, "auditor-one", "AUDITOR");
        token("foreign", tenantB, "foreign-analyst", "ANALYST");
        token("no-role", tenantA, "unrelated-user", "UNRELATED");
    }

    @AfterEach
    void cleanUp() {
        if (tenantA == null || tenantB == null) {
            return;
        }

        for (String table : List.of(
                "investigation_note",
                "exception_case",
                "reconciliation_run_request",
                "reconciliation_run_item",
                "reconciliation_run",
                "import_batch"
        )) {
            jdbcTemplate.update(
                    "DELETE FROM " + table
                            + " WHERE tenant_id IN (?, ?)",
                    tenantA.toString(),
                    tenantB.toString()
            );
        }

        jdbcTemplate.update(
                "DELETE FROM tenant WHERE id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );
    }

    @Test
    void caseHistoryUsesStableOrderingAndRealPages() throws Exception {
        List<String> expectedIds = casesA.stream()
                .map(UUID::toString)
                .sorted(java.util.Comparator.reverseOrder())
                .toList();

        read("/api/exception-cases?page=0&size=2", "analyst")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id")
                        .value(expectedIds.get(0)))
                .andExpect(jsonPath("$.items[1].id")
                        .value(expectedIds.get(1)));

        read("/api/exception-cases?page=1&size=2", "analyst")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id")
                        .value(expectedIds.get(2)));

        read("/api/exception-cases?page=2&size=2", "analyst")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void filtersByStatusAndCurrentTokenSubject() throws Exception {
        read("/api/exception-cases?status=OPEN", "analyst")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id")
                        .value(casesA.get(2).toString()));

        read(
                "/api/exception-cases?status=IN_REVIEW&assignedToMe=true",
                "analyst"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id")
                        .value(casesA.get(0).toString()))
                .andExpect(jsonPath("$.items[0].assignedTo")
                        .value("analyst-one"));

        read(
                "/api/exception-cases?assignedToMe=true",
                "analyst-two"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id")
                        .value(casesA.get(1).toString()));

        read(
                "/api/exception-cases?assignedToMe=false",
                "analyst"
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void allReadRolesCanUseBothEndpoints() throws Exception {
        for (String token : List.of(
                "importer",
                "analyst",
                "approver",
                "auditor"
        )) {
            read("/api/exception-cases", token)
                    .andExpect(status().isOk());

            read(notesUrl(casesA.get(0)), token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));
        }
    }

    @Test
    void tenantBCannotReadTenantACasesOrNotes() throws Exception {
        read("/api/exception-cases", "foreign")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id")
                        .value(caseB.toString()));

        read(notesUrl(casesA.get(0)), "foreign")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));

        read(notesUrl(caseB), "analyst")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void notesAreChronologicalPaginatedAndDoNotChangeVersion()
            throws Exception {
        UUID caseId = casesA.get(0);

        caseService.addNote(
                tenantA, caseId, 1, "analyst-one", "First note"
        );
        caseService.addNote(
                tenantA, caseId, 2, "analyst-one", "Second note"
        );
        caseService.addNote(
                tenantA, caseId, 3, "analyst-one", "Third note"
        );

        // Give all notes the same timestamp to test deterministic ordering.
        jdbcTemplate.update(
                """
                UPDATE investigation_note
                SET created_at_utc = ?
                WHERE tenant_id = ?
                  AND case_id = ?
                """,
                Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")),
                tenantA.toString(),
                caseId.toString()
        );

        List<String> expectedIds = jdbcTemplate.query(
                """
                SELECT id
                FROM investigation_note
                WHERE tenant_id = ?
                  AND case_id = ?
                ORDER BY created_at_utc ASC, id ASC
                """,
                (rs, row) -> rs.getString("id"),
                tenantA.toString(),
                caseId.toString()
        );

        read(notesUrl(caseId) + "?page=0&size=2", "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id")
                        .value(expectedIds.get(0)))
                .andExpect(jsonPath("$.items[1].id")
                        .value(expectedIds.get(1)))
                .andExpect(jsonPath("$.items[0].authorSubject")
                        .value("analyst-one"));

        read(notesUrl(caseId) + "?page=1&size=2", "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id")
                        .value(expectedIds.get(2)));

        assertEquals(4L, caseService.get(tenantA, caseId).version());
    }

    @Test
    void emptyNoteHistoryAndMissingCaseAreDifferent() throws Exception {
        read(notesUrl(casesA.get(2)), "analyst")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));

        read(notesUrl(UUID.randomUUID()), "analyst")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidPaginationAndQueryParameters() throws Exception {
        for (String parameters : List.of(
                "page=-1",
                "size=0",
                "size=101"
        )) {
            read("/api/exception-cases?" + parameters, "analyst")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_PAGINATION"));

            read(notesUrl(casesA.get(0)) + "?" + parameters, "analyst")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_PAGINATION"));
        }

        for (String parameters : List.of(
                "status=UNKNOWN",
                "assignedToMe=invalid",
                "page=abc"
        )) {
            read("/api/exception-cases?" + parameters, "analyst")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_CASE_QUERY_PARAMETER"));
        }

        read("/api/exception-cases/not-a-uuid/notes", "analyst")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_CASE_QUERY_PARAMETER"));
    }

    @Test
    void requiresAuthenticationAndAnApplicationRole() throws Exception {
        for (String url : List.of(
                "/api/exception-cases",
                notesUrl(casesA.get(0))
        )) {
            mockMvc.perform(get(url))
                    .andExpect(status().isUnauthorized());

            read(url, "no-role")
                    .andExpect(status().isForbidden());
        }
    }

    private ResultActions read(
            String url,
            String token
    ) throws Exception {
        return mockMvc.perform(
                get(url).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token
                )
        );
    }

    private String notesUrl(UUID caseId) {
        return "/api/exception-cases/" + caseId + "/notes";
    }

    private void token(
            String tokenValue,
            UUID tenantId,
            String subject,
            String role
    ) {
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(subject)
                .claim("tenant_id", tenantId.toString())
                .claim(
                        "realm_access",
                        Map.of("roles", List.of(role))
                )
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(jwtDecoder.decode(tokenValue)).thenReturn(jwt);
    }

    private List<UUID> createCases(UUID tenantId, int count) {
        UUID settlementImportId = insertImport(
                tenantId,
                "PROCESSOR_SETTLEMENT"
        );

        UUID bankImportId = insertImport(
                tenantId,
                "BANK_STATEMENT"
        );

        UUID runId = UUID.randomUUID();

        List<BankReconciliationPreview.BatchResult> results =
                new ArrayList<>();

        for (int i = 0; i < count; i++) {
            results.add(
                    new BankReconciliationPreview.BatchResult(
                            UUID.randomUUID(),
                            "PROCESSOR",
                            "ACCOUNT-1",
                            "SETTLEMENT-" + i,
                            MatchOutcome.AMOUNT_MISMATCH,
                            List.of(UUID.randomUUID()),
                            100L,
                            "INR"
                    )
            );
        }

        BankReconciliationPreview report =
                new BankReconciliationPreview(
                        settlementImportId,
                        bankImportId,
                        count,
                        count,
                        0,
                        count,
                        0,
                        false,
                        results,
                        List.of()
                );

        StoredReconciliationRun run =
                new StoredReconciliationRun(
                        runId,
                        "EXACT_SETTLEMENT_BANK_V1",
                        1,
                        "fixture-analyst",
                        Instant.parse("2026-01-01T00:00:00Z"),
                        report
                );

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        transaction -> runRepository.save(tenantId, run)
                );

        List<UUID> ids = new ArrayList<>();

        for (BankReconciliationPreview.BatchResult result : results) {
            StoredExceptionCase stored = caseService.open(
                    tenantId,
                    "fixture-analyst",
                    runId,
                    CaseEvidenceReference.ItemType.BATCH,
                    result.settlementBatchId()
            );

            ids.add(stored.details().id());
        }

        return ids;
    }

    private UUID insertImport(UUID tenantId, String sourceType) {
        UUID importId = UUID.randomUUID();

        String hash = UUID.randomUUID().toString()
                .replace("-", "")
                .repeat(2);

        jdbcTemplate.update(
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                importId.toString(),
                tenantId.toString(),
                sourceType,
                "QUERY_TEST",
                importId + ".csv",
                "test://case-query/" + importId,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-01-01"),
                3L,
                2L
        );

        return importId;
    }
}