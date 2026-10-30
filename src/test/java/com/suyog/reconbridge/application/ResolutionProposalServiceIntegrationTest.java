package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.ResolutionProposalResult;
import com.suyog.reconbridge.application.model.StoredExceptionCase;
import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import com.suyog.reconbridge.domain.model.ExceptionCase;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.domain.model.ResolutionProposal;
import com.suyog.reconbridge.domain.model.ResolutionProposalStatus;
import com.suyog.reconbridge.domain.model.ResolutionType;
import com.suyog.reconbridge.persistence.repository.ExceptionCaseRepository;
import com.suyog.reconbridge.persistence.repository.ResolutionProposalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class ResolutionProposalServiceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ExceptionCaseService caseService;

    @Autowired
    private ResolutionProposalService service;

    @MockitoSpyBean
    private ExceptionCaseRepository caseRepository;

    @MockitoSpyBean
    private ResolutionProposalRepository proposalRepository;

    private ExceptionCaseRepository caseSpy;
    private ResolutionProposalRepository proposalSpy;

    private UUID tenantA;
    private UUID tenantB;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        caseSpy = AopTestUtils.getUltimateTargetObject(caseRepository);
        proposalSpy = AopTestUtils.getUltimateTargetObject(proposalRepository);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantA.toString(),
                "Proposal test tenant A"
        );

        jdbc.update(
                "INSERT INTO tenant (id, name) VALUES (?, ?)",
                tenantB.toString(),
                "Proposal test tenant B"
        );

        caseId = createClaimedCase(tenantA);
    }

    @AfterEach
    void cleanUp() {
        if (caseSpy != null) {
            reset(caseSpy);
        }
        if (proposalSpy != null) {
            reset(proposalSpy);
        }

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
    void submissionPersistsProposalAndAdvancesCaseAtomically() {
        ResolutionProposalResult result = submit();

        assertEquals(2, result.exceptionCase().version());
        assertEquals(
                ExceptionCaseStatus.AWAITING_APPROVAL,
                result.exceptionCase().details().status()
        );
        assertEquals(
                ResolutionProposalStatus.PENDING,
                result.proposal().status()
        );
        assertEquals("analyst", result.proposal().proposedBy());
        assertEquals(
                result.proposal(),
                service.get(tenantA, caseId, result.proposal().id())
        );
        assertEquals(1, countProposals());
    }

    @Test
    void independentApprovalResolvesCaseAndRecordsDecision() {
        ResolutionProposalResult pending = submit();

        ResolutionProposalResult approved = service.approve(
                tenantA,
                caseId,
                pending.proposal().id(),
                2,
                "approver",
                "Supporting bank advice verified."
        );

        assertEquals(3, approved.exceptionCase().version());
        assertEquals(
                ExceptionCaseStatus.RESOLVED,
                approved.exceptionCase().details().status()
        );
        assertEquals(
                ResolutionProposalStatus.APPROVED,
                service.get(
                        tenantA,
                        caseId,
                        pending.proposal().id()
                ).status()
        );
        assertEquals("approver", approved.proposal().decidedBy());

        expectProblem(
                409,
                "CASE_NOT_IN_REVIEW",
                () -> caseService.addNote(
                        tenantA, caseId, 3, "analyst", "Late note"
                )
        );
    }

    @Test
    void rejectionAllowsNewProposalAndPreservesHistory() {
        ResolutionProposalResult first = submit();

        ResolutionProposalResult rejected = service.reject(
                tenantA,
                caseId,
                first.proposal().id(),
                2,
                "approver",
                "Provide additional evidence."
        );

        assertEquals(
                ExceptionCaseStatus.IN_REVIEW,
                rejected.exceptionCase().details().status()
        );
        assertEquals("analyst", rejected.exceptionCase().details().assignedTo());

        ResolutionProposalResult second = service.submit(
                tenantA,
                caseId,
                3,
                "analyst",
                ResolutionType.DATA_CORRECTION_CONFIRMED,
                "Additional correction evidence verified."
        );

        assertNotEquals(first.proposal().id(), second.proposal().id());
        assertEquals(4, second.exceptionCase().version());
        assertEquals(2, countProposals());

        assertEquals(
                ResolutionProposalStatus.REJECTED,
                service.get(tenantA, caseId, first.proposal().id()).status()
        );

        expectProblem(
                409,
                "PROPOSAL_NOT_PENDING",
                () -> service.approve(
                        tenantA, caseId, first.proposal().id(),
                        4, "approver", "Attempt old proposal"
                )
        );

        assertEquals(
                ResolutionProposalStatus.PENDING,
                service.get(tenantA, caseId, second.proposal().id()).status()
        );
    }

    @Test
    void proposerCannotApproveOrRejectOwnProposal() {
        ResolutionProposalResult pending = submit();

        expectProblem(
                403,
                "SELF_APPROVAL_FORBIDDEN",
                () -> service.approve(
                        tenantA, caseId, pending.proposal().id(),
                        2, "analyst", "Self approval"
                )
        );

        expectProblem(
                403,
                "SELF_APPROVAL_FORBIDDEN",
                () -> service.reject(
                        tenantA, caseId, pending.proposal().id(),
                        2, "analyst", "Self rejection"
                )
        );

        assertEquals(2, caseService.get(tenantA, caseId).version());
        assertEquals(
                ResolutionProposalStatus.PENDING,
                service.get(tenantA, caseId, pending.proposal().id()).status()
        );
    }

    @Test
    void rejectsWrongAssigneeStaleVersionAndInvalidText() {
        expectProblem(
                403,
                "CASE_NOT_ASSIGNED_TO_ACTOR",
                () -> service.submit(
                        tenantA, caseId, 1, "other-analyst",
                        ResolutionType.ACCEPTED_VARIANCE, "Reason"
                )
        );

        expectProblem(
                409,
                "STALE_CASE_VERSION",
                () -> service.submit(
                        tenantA, caseId, 0, "analyst",
                        ResolutionType.ACCEPTED_VARIANCE, "Reason"
                )
        );

        expectProblem(
                400,
                "INVALID_PROPOSAL_RATIONALE",
                () -> service.submit(
                        tenantA, caseId, 1, "analyst",
                        ResolutionType.ACCEPTED_VARIANCE, " "
                )
        );

        assertEquals(0, countProposals());
        assertEquals(1, caseService.get(tenantA, caseId).version());

        ResolutionProposalResult pending = submit();

        expectProblem(
                400,
                "INVALID_DECISION_COMMENT",
                () -> service.approve(
                        tenantA, caseId, pending.proposal().id(),
                        2, "approver", " "
                )
        );

        expectProblem(
                409,
                "STALE_CASE_VERSION",
                () -> service.approve(
                        tenantA, caseId, pending.proposal().id(),
                        1, "approver", "Verified"
                )
        );
    }

    @Test
    void rejectsForeignTenantAndWrongCaseProposalReferences() {
        ResolutionProposalResult pending = submit();

        expectProblem(
                404,
                "CASE_NOT_FOUND",
                () -> service.get(tenantB, caseId, pending.proposal().id())
        );

        expectProblem(
                404,
                "CASE_NOT_FOUND",
                () -> service.submit(
                        tenantB, caseId, 2, "analyst",
                        ResolutionType.ACCEPTED_VARIANCE, "Reason"
                )
        );

        expectProblem(
                404,
                "CASE_NOT_FOUND",
                () -> service.approve(
                        tenantB, caseId, pending.proposal().id(),
                        2, "approver", "Verified"
                )
        );

        UUID otherCase = createClaimedCase(tenantA);

        expectProblem(
                404,
                "PROPOSAL_NOT_FOUND",
                () -> service.get(
                        tenantA, otherCase, pending.proposal().id()
                )
        );

        expectProblem(
                404,
                "PROPOSAL_NOT_FOUND",
                () -> service.approve(
                        tenantA, otherCase, pending.proposal().id(),
                        1, "approver", "Wrong case"
                )
        );
    }

    @Test
    void pendingCaseRejectsNotesAndAnotherSubmission() {
        submit();

        expectProblem(
                409,
                "CASE_NOT_IN_REVIEW",
                () -> caseService.addNote(
                        tenantA, caseId, 2, "analyst", "New note"
                )
        );

        expectProblem(
                409,
                "CASE_NOT_IN_REVIEW",
                () -> service.submit(
                        tenantA, caseId, 2, "analyst",
                        ResolutionType.ACCEPTED_VARIANCE, "Another proposal"
                )
        );

        assertEquals(1, countProposals());
    }

    @Test
    void rollsBackCaseAndProposalWhenInsertFailsAfterWriting() {
        IllegalStateException injectedFailure =
                new IllegalStateException("Injected insert failure");

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw injectedFailure;
        }).when(proposalSpy).insert(any(ResolutionProposal.class));

        var exception = assertThrows(
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                this::submit
        );

        // Confirm the failure came from our deliberate injection.
        assertSame(injectedFailure, exception.getMostSpecificCause());

        // The real INSERT executed, but its transaction must have rolled back.
        assertEquals(0, countProposals());

        StoredExceptionCase current = caseService.get(tenantA, caseId);

        assertEquals(1, current.version());
        assertEquals(
                ExceptionCaseStatus.IN_REVIEW,
                current.details().status()
        );
        assertEquals("analyst", current.details().assignedTo());
    }

    @Test
    void rollsBackCaseAndDecisionWhenDecisionWriteFails() {
        ResolutionProposalResult pending = submit();

        IllegalStateException injectedFailure =
                new IllegalStateException("Injected decision failure");

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw injectedFailure;
        }).when(proposalSpy).recordDecision(any(ResolutionProposal.class));

        var exception = assertThrows(
                org.springframework.dao.InvalidDataAccessApiUsageException.class,
                () -> service.approve(
                        tenantA,
                        caseId,
                        pending.proposal().id(),
                        2,
                        "approver",
                        "Verified"
                )
        );

        // Confirm the failure came from our deliberate injection.
        assertSame(injectedFailure, exception.getMostSpecificCause());

        StoredExceptionCase current = caseService.get(tenantA, caseId);

        // Both the case transition and its version increment must roll back.
        assertEquals(2, current.version());
        assertEquals(
                ExceptionCaseStatus.AWAITING_APPROVAL,
                current.details().status()
        );

        ResolutionProposal unchanged = service.get(
                tenantA,
                caseId,
                pending.proposal().id()
        );

        // The real decision UPDATE executed but must not remain committed.
        assertEquals(
                ResolutionProposalStatus.PENDING,
                unchanged.status()
        );
        assertNull(unchanged.decidedBy());
        assertNull(unchanged.decisionComment());
        assertNull(unchanged.decidedAt());

        assertEquals(
                pending.proposal().rationale(),
                unchanged.rationale()
        );
        assertEquals(
                pending.proposal().proposedBy(),
                unchanged.proposedBy()
        );
    }

    @Test
    void competingSubmissionsCommitExactlyOneProposal() throws Exception {
        synchronizeCaseUpdates();

        List<String> outcomes = compete(
                () -> {
                    submit();
                    return "COMMITTED";
                },
                () -> {
                    submit();
                    return "COMMITTED";
                }
        );

        assertEquals(
                List.of("COMMITTED", "STALE_CASE_VERSION"),
                outcomes.stream().sorted().toList()
        );

        assertEquals(1, countProposals());
        assertEquals(2, caseService.get(tenantA, caseId).version());
    }

    @Test
    void competingApprovalAndRejectionCommitExactlyOneDecision()
            throws Exception {
        ResolutionProposalResult pending = submit();

        synchronizeCaseUpdates();

        List<String> outcomes = compete(
                () -> {
                    service.approve(
                            tenantA, caseId, pending.proposal().id(),
                            2, "approver-one", "Verified"
                    );
                    return "COMMITTED";
                },
                () -> {
                    service.reject(
                            tenantA, caseId, pending.proposal().id(),
                            2, "approver-two", "More evidence required"
                    );
                    return "COMMITTED";
                }
        );

        assertEquals(
                List.of("COMMITTED", "STALE_CASE_VERSION"),
                outcomes.stream().sorted().toList()
        );

        StoredExceptionCase current = caseService.get(tenantA, caseId);
        ResolutionProposal decided = service.get(
                tenantA, caseId, pending.proposal().id()
        );

        assertEquals(3, current.version());
        assertNotEquals(ResolutionProposalStatus.PENDING, decided.status());

        if (decided.status() == ResolutionProposalStatus.APPROVED) {
            assertEquals(ExceptionCaseStatus.RESOLVED, current.details().status());
            assertEquals("approver-one", decided.decidedBy());
        } else {
            assertEquals(ExceptionCaseStatus.IN_REVIEW, current.details().status());
            assertEquals("approver-two", decided.decidedBy());
        }
    }

    private void synchronizeCaseUpdates() {
        CountDownLatch bothReady = new CountDownLatch(2);

        doAnswer(invocation -> {
            bothReady.countDown();

            if (!bothReady.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(
                        "Both workers did not reach the case update."
                );
            }

            return invocation.callRealMethod();
        }).when(caseSpy).update(any(ExceptionCase.class), anyLong());
    }

    private List<String> compete(
            Callable<String> first,
            Callable<String> second
    ) throws Exception {
        var executor = Executors.newFixedThreadPool(2);

        try {
            var firstResult = executor.submit(() -> outcome(first));
            var secondResult = executor.submit(() -> outcome(second));

            return List.of(
                    firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private String outcome(Callable<String> action) throws Exception {
        try {
            return action.call();
        } catch (ExceptionCaseOperationException exception) {
            if (!"STALE_CASE_VERSION".equals(exception.getCode())) {
                throw exception;
            }
            return exception.getCode();
        }
    }

    private ResolutionProposalResult submit() {
        return service.submit(
                tenantA,
                caseId,
                1,
                "analyst",
                ResolutionType.TIMING_DIFFERENCE_CONFIRMED,
                "The later statement confirms this credit."
        );
    }

    private void expectProblem(
            int status,
            String code,
            Runnable action
    ) {
        ExceptionCaseOperationException exception = assertThrows(
                ExceptionCaseOperationException.class,
                action::run
        );

        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }

    private long countProposals() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resolution_proposal WHERE tenant_id = ?",
                Long.class,
                tenantA.toString()
        );
        return count == null ? 0 : count;
    }

    private UUID createClaimedCase(UUID tenantId) {
        UUID settlementImport = insertImport(
                tenantId, "PROCESSOR_SETTLEMENT"
        );
        UUID bankImport = insertImport(
                tenantId, "BANK_STATEMENT"
        );
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
                tenantId.toString(),
                runId.toString(),
                settlementImport.toString(),
                bankImport.toString(),
                "EXACT_SETTLEMENT_BANK_V1",
                1,
                "fixture",
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                1, 0, 0, 1, 0, false
        );

        /*
         * These service tests need only the saved evidence identity/outcome.
         * They do not deserialize the reconciliation report payload.
         */
        jdbc.update(
                """
                INSERT INTO reconciliation_run_item (
                    tenant_id, run_id, item_type,
                    evidence_id, outcome, payload
                )
                VALUES (?, ?, 'BATCH', ?, 'MISSING_COUNTERPART', ?)
                """,
                tenantId.toString(),
                runId.toString(),
                evidenceId.toString(),
                "{}"
        );

        StoredExceptionCase opened = caseService.open(
                tenantId,
                "analyst",
                runId,
                CaseEvidenceReference.ItemType.BATCH,
                evidenceId
        );

        caseService.claim(
                tenantId,
                opened.details().id(),
                opened.version(),
                "analyst"
        );

        return opened.details().id();
    }

    private UUID insertImport(UUID tenantId, String sourceType) {
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
                tenantId.toString(),
                sourceType,
                "PROPOSAL_TEST",
                id + ".csv",
                "test://proposal/" + id,
                hash,
                "READY",
                java.sql.Date.valueOf("2026-01-01"),
                1L,
                2L
        );

        return id;
    }
}