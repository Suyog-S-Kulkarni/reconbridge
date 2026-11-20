package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.ExceptionCaseService;
import com.suyog.reconbridge.application.ResolutionProposalService;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.domain.model.ResolutionProposalStatus;
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
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResolutionProposalControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExceptionCaseService caseService;

    @Autowired
    private ResolutionProposalService proposalService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Proposal HTTP tenant A"
        );

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Proposal HTTP tenant B"
        );

        caseId = createClaimedCase();

        token("analyst", tenantA, "analyst-subject", "ANALYST");
        token("other-analyst", tenantA, "other-subject", "ANALYST");
        token("approver", tenantA, "approver-subject", "APPROVER");
        token("auditor", tenantA, "auditor-subject", "AUDITOR");
        token("importer", tenantA, "importer-subject", "IMPORTER");

        // Same subject as the assigned analyst, but holding both roles.
        token(
                "dual-role",
                tenantA,
                "analyst-subject",
                "ANALYST",
                "APPROVER"
        );

        token(
                "foreign",
                tenantB,
                "foreign-subject",
                "ANALYST",
                "APPROVER"
        );

        token("no-role", tenantA, "unrelated-subject", "UNRELATED");
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
    void analystSubmitsAndIndependentApproverResolvesCase()
            throws Exception {
        send(base(), "analyst", submitBody(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exceptionCase.version").value(2))
                .andExpect(jsonPath("$.exceptionCase.details.status")
                        .value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.proposal.status").value("PENDING"))
                .andExpect(jsonPath("$.proposal.proposedBy")
                        .value("analyst-subject"))
                .andExpect(jsonPath("$.proposal.tenantId")
                        .value(tenantA.toString()))
                .andExpect(header().exists(HttpHeaders.LOCATION));

        UUID proposalId = savedProposalId();

        read(proposalUrl(proposalId), "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(proposalId.toString()));

        send(
                proposalUrl(proposalId) + "/approve",
                "approver",
                decisionBody(2)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptionCase.version").value(3))
                .andExpect(jsonPath("$.exceptionCase.details.status")
                        .value("RESOLVED"))
                .andExpect(jsonPath("$.proposal.status").value("APPROVED"))
                .andExpect(jsonPath("$.proposal.decidedBy")
                        .value("approver-subject"));

        assertEquals(
                ExceptionCaseStatus.RESOLVED,
                caseService.get(tenantA, caseId).details().status()
        );
    }

    @Test
    void rejectionReturnsCaseToReviewAndAllowsResubmission()
            throws Exception {
        UUID firstProposal = pending();

        send(
                proposalUrl(firstProposal) + "/reject",
                "approver",
                decisionBody(2)
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptionCase.version").value(3))
                .andExpect(jsonPath("$.exceptionCase.details.status")
                        .value("IN_REVIEW"))
                .andExpect(jsonPath("$.proposal.status").value("REJECTED"));

        send(base(), "analyst", submitBody(3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exceptionCase.version").value(4))
                .andExpect(jsonPath("$.proposal.status").value("PENDING"));

        read(proposalUrl(firstProposal), "auditor")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertEquals(2L, proposalCount());
    }

    @Test
    void dualRoleProposerCannotApproveOwnProposal() throws Exception {
        send(base(), "dual-role", submitBody(1))
                .andExpect(status().isCreated());

        UUID proposalId = savedProposalId();

        send(
                proposalUrl(proposalId) + "/approve",
                "dual-role",
                decisionBody(2)
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("SELF_APPROVAL_FORBIDDEN"));

        assertStillPending(proposalId);
    }

    @Test
    void dualRoleProposerCannotRejectOwnProposal() throws Exception {
        send(base(), "dual-role", submitBody(1))
                .andExpect(status().isCreated());

        UUID proposalId = savedProposalId();

        send(
                proposalUrl(proposalId) + "/reject",
                "dual-role",
                decisionBody(2)
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("SELF_APPROVAL_FORBIDDEN"));

        assertStillPending(proposalId);
    }

    @Test
    void submissionRequiresAnalystRoleAndAssignment() throws Exception {
        for (String token : List.of("approver", "auditor", "importer")) {
            send(base(), token, submitBody(1))
                    .andExpect(status().isForbidden());
        }

        send(base(), "other-analyst", submitBody(1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("CASE_NOT_ASSIGNED_TO_ACTOR"));

        assertEquals(0L, proposalCount());
        assertEquals(1L, caseService.get(tenantA, caseId).version());
    }

    @Test
    void decisionsRequireApproverRole() throws Exception {
        UUID proposalId = pending();

        for (String token : List.of("analyst", "auditor", "importer")) {
            for (String action : List.of("approve", "reject")) {
                send(
                        proposalUrl(proposalId) + "/" + action,
                        token,
                        decisionBody(2)
                ).andExpect(status().isForbidden());
            }
        }

        assertStillPending(proposalId);
    }

    @Test
    void allReadRolesCanRetrieveProposal() throws Exception {
        UUID proposalId = pending();

        for (String token : List.of(
                "analyst", "approver", "auditor", "importer"
        )) {
            read(proposalUrl(proposalId), token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id")
                            .value(proposalId.toString()));
        }
    }

    @Test
    void foreignTenantCannotUseAnyProposalOperation() throws Exception {
        UUID proposalId = pending();

        send(base(), "foreign", submitBody(2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));

        read(proposalUrl(proposalId), "foreign")
                .andExpect(status().isNotFound());

        for (String action : List.of("approve", "reject")) {
            send(
                    proposalUrl(proposalId) + "/" + action,
                    "foreign",
                    decisionBody(2)
            )
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
        }

        assertStillPending(proposalId);
    }

    @Test
    void missingAuthenticationAndUnrelatedRoleAreRejected()
            throws Exception {
        UUID proposalId = pending();

        mvc.perform(get(proposalUrl(proposalId)))
                .andExpect(status().isUnauthorized());

        read(proposalUrl(proposalId), "no-role")
                .andExpect(status().isForbidden());

        for (String url : List.of(
                base(),
                proposalUrl(proposalId) + "/approve",
                proposalUrl(proposalId) + "/reject"
        )) {
            mvc.perform(
                    post(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
            ).andExpect(status().isUnauthorized());

            send(url, "no-role", "{}")
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void validatesBodiesAndIdentifiers() throws Exception {
        for (String body : List.of(
                "{}",
                """
                {
                  "expectedVersion": -1,
                  "resolutionType": "ACCEPTED_VARIANCE",
                  "rationale": "Reason"
                }
                """,
                """
                {
                  "expectedVersion": 1,
                  "resolutionType": "UNKNOWN",
                  "rationale": "Reason"
                }
                """,
                """
                {
                  "expectedVersion": 1,
                  "resolutionType": "ACCEPTED_VARIANCE",
                  "rationale": " "
                }
                """,
                json.writeValueAsString(Map.of(
                        "expectedVersion", 1,
                        "resolutionType", "ACCEPTED_VARIANCE",
                        "rationale", "x".repeat(4001)
                )),
                "{"
        )) {
            send(base(), "analyst", body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_PROPOSAL_REQUEST"));
        }

        UUID proposalId = pending();

        for (String body : List.of(
                "{}",
                "{\"expectedVersion\":2,\"comment\":\" \"}",
                "{\"expectedVersion\":-1,\"comment\":\"Verified\"}",
                json.writeValueAsString(Map.of(
                        "expectedVersion", 2,
                        "comment", "x".repeat(4001)
                ))
        )) {
            for (String action : List.of("approve", "reject")) {
                send(
                        proposalUrl(proposalId) + "/" + action,
                        "approver",
                        body
                )
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code")
                                .value("INVALID_PROPOSAL_REQUEST"));
            }
        }

        read(base() + "/not-a-uuid", "auditor")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_PROPOSAL_REQUEST"));

        read(
                "/api/exception-cases/not-a-uuid/proposals/" + proposalId,
                "auditor"
        ).andExpect(status().isBadRequest());

        assertStillPending(proposalId);
    }

    @Test
    void staleVersionAndRepeatedDecisionReturnConflict()
            throws Exception {
        UUID proposalId = pending();

        send(
                proposalUrl(proposalId) + "/approve",
                "approver",
                decisionBody(1)
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_CASE_VERSION"));

        send(
                proposalUrl(proposalId) + "/approve",
                "approver",
                decisionBody(2)
        ).andExpect(status().isOk());

        send(
                proposalUrl(proposalId) + "/reject",
                "approver",
                decisionBody(3)
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_PENDING"));

        assertEquals(3L, caseService.get(tenantA, caseId).version());
    }

    @Test
    void missingProposalReturnsNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();

        read(proposalUrl(missingId), "auditor")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_FOUND"));

        send(
                proposalUrl(missingId) + "/approve",
                "approver",
                decisionBody(1)
        )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_FOUND"));
    }

    private String base() {
        return "/api/exception-cases/" + caseId + "/proposals";
    }

    private String proposalUrl(UUID proposalId) {
        return base() + "/" + proposalId;
    }

    private ResultActions read(String url, String token) throws Exception {
        return mvc.perform(
                get(url).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + token
                )
        );
    }

    private ResultActions send(
            String url,
            String token,
            String body
    ) throws Exception {
        return mvc.perform(
                post(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    private String submitBody(long version) {
        return json.writeValueAsString(Map.of(
                "expectedVersion", version,
                "resolutionType", "TIMING_DIFFERENCE_CONFIRMED",
                "rationale", "The later statement confirms this credit."
        ));
    }

    private String decisionBody(long version) {
        return json.writeValueAsString(Map.of(
                "expectedVersion", version,
                "comment", "Supporting evidence reviewed."
        ));
    }

    private UUID pending() {
        return proposalService.submit(
                tenantA,
                caseId,
                1,
                "analyst-subject",
                ResolutionType.TIMING_DIFFERENCE_CONFIRMED,
                "The later statement confirms this credit."
        ).proposal().id();
    }

    private UUID savedProposalId() {
        return UUID.fromString(
                jdbc.queryForObject(
                        """
                        SELECT id FROM resolution_proposal
                        WHERE tenant_id = ? AND case_id = ?
                        """,
                        String.class,
                        tenantA.toString(),
                        caseId.toString()
                )
        );
    }

    private long proposalCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resolution_proposal WHERE tenant_id = ?",
                Long.class,
                tenantA.toString()
        );
        return count == null ? 0 : count;
    }

    private void assertStillPending(UUID proposalId) {
        assertEquals(2L, caseService.get(tenantA, caseId).version());
        assertEquals(
                ExceptionCaseStatus.AWAITING_APPROVAL,
                caseService.get(tenantA, caseId).details().status()
        );
        assertEquals(
                ResolutionProposalStatus.PENDING,
                proposalService.get(tenantA, caseId, proposalId).status()
        );
    }

    private void token(
            String tokenValue,
            UUID tenantId,
            String subject,
            String... roles
    ) {
        Jwt jwt = Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(subject)
                .claim("tenant_id", tenantId.toString())
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(jwtDecoder.decode(tokenValue)).thenReturn(jwt);
    }

    private UUID createClaimedCase() {
        UUID settlementImport = insertImport("PROCESSOR_SETTLEMENT");
        UUID bankImport = insertImport("BANK_STATEMENT");
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
                tenantA.toString(),
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
                tenantA.toString(),
                runId.toString(),
                evidenceId.toString(),
                "{}"
        );

        StoredExceptionCase opened = caseService.open(
                tenantA,
                "analyst-subject",
                runId,
                CaseEvidenceReference.ItemType.BATCH,
                evidenceId
        );

        caseService.claim(
                tenantA,
                opened.details().id(),
                opened.version(),
                "analyst-subject"
        );

        return opened.details().id();
    }

    private UUID insertImport(String sourceType) {
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
                tenantA.toString(),
                sourceType,
                "PROPOSAL_HTTP_TEST",
                id + ".csv",
                "test://proposal-http/" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-01-01"),
                1L,
                2L
        );

        return id;
    }
}