package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ExceptionCaseService;
import com.suyog.reconbridge.application.ResolutionProposalService;
import com.suyog.reconbridge.application.model.ResolutionProposalResult;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.ResolutionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResolutionProposalQueryIntegrationTest {

    private static final String QUEUE =
            "/api/resolution-proposals/pending";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExceptionCaseService cases;

    @Autowired
    private ResolutionProposalService proposals;

    @MockitoBean
    private JwtDecoder decoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        for (UUID tenant : List.of(tenantA, tenantB)) {
            jdbc.update(
                    "INSERT INTO tenant (id, name) VALUES (?, ?)",
                    tenant.toString(),
                    "Proposal query test " + tenant
            );
        }

        caseId = createClaimedCase(tenantA, "analyst");

        token("analyst", tenantA, "analyst", "ANALYST");
        token("approver", tenantA, "approver", "APPROVER");
        token("auditor", tenantA, "auditor", "AUDITOR");
        token("importer", tenantA, "importer", "IMPORTER");
        token("dual", tenantA, "analyst", "ANALYST", "APPROVER");
        token("foreign", tenantB, "foreign", "APPROVER");
        token("no-role", tenantA, "outsider", "UNRELATED");
    }

    @AfterEach
    void cleanUp() {
        if (tenantA == null || tenantB == null) {
            return;
        }

        for (String table : List.of(
                "resolution_proposal",
                "investigation_note",
                "exception_case",
                "reconciliation_run_request",
                "reconciliation_run_item",
                "reconciliation_run",
                "import_batch"
        )) {
            jdbc.update(
                    "DELETE FROM " + table
                            + " WHERE tenant_id IN (?, ?)",
                    tenantA.toString(),
                    tenantB.toString()
            );
        }

        jdbc.update(
                "DELETE FROM tenant WHERE id IN (?, ?)",
                tenantA.toString(),
                tenantB.toString()
        );
    }

    @Test
    void existingCaseWithoutProposalsHasEmptyHistory() throws Exception {
        read(history(caseId), "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void allReadRolesCanReadHistory() throws Exception {
        ResolutionProposalResult submitted =
                submit(tenantA, caseId, "analyst");

        for (String role : List.of(
                "analyst", "approver", "auditor", "importer"
        )) {
            read(history(caseId), role)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(
                            submitted.proposal().id().toString()
                    ));
        }
    }

    @Test
    void missingAndForeignCasesReturnNotFound() throws Exception {
        submit(tenantA, caseId, "analyst");

        read(history(UUID.randomUUID()), "analyst")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));

        read(history(caseId), "foreign")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void pendingQueueRequiresApproverRole() throws Exception {
        submit(tenantA, caseId, "analyst");

        read(QUEUE, "approver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        for (String role : List.of(
                "analyst", "auditor", "importer", "no-role"
        )) {
            read(QUEUE, role)
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void missingAuthenticationAndUnrelatedRolesAreRejected()
            throws Exception {
        mvc.perform(get(QUEUE))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(history(caseId)))
                .andExpect(status().isUnauthorized());

        read(history(caseId), "no-role")
                .andExpect(status().isForbidden());
    }

    @Test
    void dualRoleUserCannotSeeOwnProposalInActionableQueue()
            throws Exception {
        ResolutionProposalResult own =
                submit(tenantA, caseId, "analyst");

        UUID otherCase = createClaimedCase(tenantA, "other-analyst");

        ResolutionProposalResult other =
                submit(tenantA, otherCase, "other-analyst");

        read(QUEUE, "dual")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].proposalId").value(
                        other.proposal().id().toString()
                ));

        // Own proposal remains visible in history.
        read(history(caseId), "dual")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(
                        own.proposal().id().toString()
                ));

        // Direct access still cannot bypass the decision rule.
        mvc.perform(
                        post(history(caseId) + "/"
                                + own.proposal().id() + "/approve")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer dual")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "expectedVersion": 2,
                                  "comment": "Attempted self approval."
                                }
                                """)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("SELF_APPROVAL_FORBIDDEN"));
    }

    @Test
    void queueIsTenantScoped() throws Exception {
        submit(tenantA, caseId, "analyst");

        UUID foreignCase = createClaimedCase(tenantB, "foreign-maker");

        ResolutionProposalResult foreignProposal =
                submit(tenantB, foreignCase, "foreign-maker");

        read(QUEUE, "foreign")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].caseId").value(
                        foreignCase.toString()
                ))
                .andExpect(jsonPath("$.items[0].proposalId").value(
                        foreignProposal.proposal().id().toString()
                ));

        read(QUEUE, "approver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].caseId").value(
                        caseId.toString()
                ));
    }

    @Test
    void historyKeepsDecisionsAndUsesStablePages() throws Exception {
        ResolutionProposalResult first =
                submit(tenantA, caseId, "analyst");

        proposals.reject(
                tenantA,
                caseId,
                first.proposal().id(),
                first.exceptionCase().version(),
                "approver",
                "More evidence required."
        );

        ResolutionProposalResult second =
                submit(tenantA, caseId, "analyst");

        proposals.approve(
                tenantA,
                caseId,
                second.proposal().id(),
                second.exceptionCase().version(),
                "approver",
                "Additional evidence accepted."
        );

        // Equal timestamps deliberately exercise the ID tie-breaker.
        Timestamp sameTime = Timestamp.from(
                Instant.parse("2026-01-01T00:00:00Z")
        );

        jdbc.update(
                """
                UPDATE resolution_proposal
                SET proposed_at_utc = ?
                WHERE tenant_id = ? AND case_id = ?
                """,
                sameTime,
                tenantA.toString(),
                caseId.toString()
        );

        List<String> expectedIds = List.of(
                first.proposal().id().toString(),
                second.proposal().id().toString()
        ).stream().sorted(java.util.Comparator.reverseOrder()).toList();

        for (int page = 0; page < 2; page++) {
            read(history(caseId) + "?page=" + page + "&size=1", "auditor")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.items[0].id").value(
                            expectedIds.get(page)
                    ));
        }

        read(history(caseId) + "?page=2&size=1", "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(2));

        read(QUEUE, "approver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void pendingQueueUsesStablePagesWithoutChangingVersions()
            throws Exception {
        ResolutionProposalResult first =
                submit(tenantA, caseId, "analyst");

        UUID secondCase = createClaimedCase(tenantA, "other-analyst");

        ResolutionProposalResult second =
                submit(tenantA, secondCase, "other-analyst");

        jdbc.update(
                """
                UPDATE resolution_proposal
                SET proposed_at_utc = ?
                WHERE tenant_id = ?
                """,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                tenantA.toString()
        );

        List<String> expectedIds = List.of(
                first.proposal().id().toString(),
                second.proposal().id().toString()
        ).stream().sorted(java.util.Comparator.reverseOrder()).toList();

        for (int page = 0; page < 2; page++) {
            read(QUEUE + "?page=" + page + "&size=1", "approver")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.items[0].proposalId").value(
                            expectedIds.get(page)
                    ))
                    .andExpect(jsonPath("$.items[0].caseVersion").value(2));
        }

        assertEquals(2L, cases.get(tenantA, caseId).version());
        assertEquals(2L, cases.get(tenantA, secondCase).version());
    }

    @Test
    void queueRequiresCaseToBeAwaitingApproval() throws Exception {
        submit(tenantA, caseId, "analyst");

        // Deliberately inconsistent fixture verifies the defensive join filter.
        jdbc.update(
                """
                UPDATE exception_case
                SET status = 'IN_REVIEW'
                WHERE tenant_id = ? AND id = ?
                """,
                tenantA.toString(),
                caseId.toString()
        );

        read(QUEUE, "approver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void invalidPaginationAndMalformedParametersReturnStructuredErrors()
            throws Exception {
        for (String url : List.of(history(caseId), QUEUE)) {
            for (String query : List.of(
                    "?page=-1",
                    "?size=0",
                    "?size=101"
            )) {
                read(url + query, "approver")
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code")
                                .value("INVALID_PAGINATION"));
            }

            read(url + "?page=abc", "approver")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_PROPOSAL_QUERY_PARAMETER"));
        }

        read("/api/exception-cases/not-a-uuid/proposals", "approver")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_PROPOSAL_QUERY_PARAMETER"));
    }

    @Test
    void decisionUsingVersionFromAnOutdatedQueueReturnsConflict()
            throws Exception {
        ResolutionProposalResult submitted =
                submit(tenantA, caseId, "analyst");

        read(QUEUE, "approver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].caseVersion").value(2));

        proposals.reject(
                tenantA,
                caseId,
                submitted.proposal().id(),
                2,
                "another-approver",
                "Further evidence needed."
        );

        mvc.perform(
                        post(history(caseId) + "/"
                                + submitted.proposal().id() + "/approve")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer approver"
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {
                                  "expectedVersion": 2,
                                  "comment": "Decision from an outdated queue."
                                }
                                """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_CASE_VERSION"));

        read(QUEUE, "approver")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private ResolutionProposalResult submit(
            UUID tenant,
            UUID targetCase,
            String actor
    ) {
        return proposals.submit(
                tenant,
                targetCase,
                cases.get(tenant, targetCase).version(),
                actor,
                ResolutionType.TIMING_DIFFERENCE_CONFIRMED,
                "Supporting evidence reviewed for this test."
        );
    }

    private String history(UUID id) {
        return "/api/exception-cases/" + id + "/proposals";
    }

    private ResultActions read(
            String url,
            String tokenValue
    ) throws Exception {
        return mvc.perform(
                get(url).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokenValue
                )
        );
    }

    private void token(
            String value,
            UUID tenant,
            String subject,
            String... roles
    ) {
        Jwt jwt = Jwt.withTokenValue(value)
                .header("alg", "RS256")
                .subject(subject)
                .claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(decoder.decode(value)).thenReturn(jwt);
    }

    private UUID createClaimedCase(UUID tenant, String analyst) {
        UUID settlementImport = insertImport(
                tenant,
                "PROCESSOR_SETTLEMENT"
        );
        UUID bankImport = insertImport(tenant, "BANK_STATEMENT");
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        jdbc.update(
                """
                INSERT INTO reconciliation_run (
                    tenant_id, id, settlement_import_id, bank_import_id,
                    rule_version, report_version, created_by,
                    created_at_utc, settlement_batch_count,
                    bank_credit_count, matched_batch_count,
                    unresolved_batch_count, orphan_bank_credit_count,
                    fully_reconciled
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenant.toString(),
                runId.toString(),
                settlementImport.toString(),
                bankImport.toString(),
                "EXACT_SETTLEMENT_BANK_V1",
                1,
                "fixture",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                1, 0, 0, 1, 0, false
        );

        jdbc.update(
                """
                INSERT INTO reconciliation_run_item (
                    tenant_id, run_id, item_type,
                    evidence_id, outcome, payload
                )
                VALUES (?, ?, 'BATCH', ?, 'MISSING_COUNTERPART', ?)
                """,
                tenant.toString(),
                runId.toString(),
                evidenceId.toString(),
                "{}"
        );

        StoredExceptionCase opened = cases.open(
                tenant,
                analyst,
                runId,
                CaseEvidenceReference.ItemType.BATCH,
                evidenceId
        );

        cases.claim(
                tenant,
                opened.details().id(),
                opened.version(),
                analyst
        );

        return opened.details().id();
    }

    private UUID insertImport(UUID tenant, String sourceType) {
        UUID id = UUID.randomUUID();

        String hash = UUID.randomUUID().toString()
                .replace("-", "")
                .repeat(2);

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
                sourceType,
                "PROPOSAL_QUERY_TEST",
                id + ".csv",
                "test://proposal-query/" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-01-01"),
                1L,
                2L
        );

        return id;
    }
}