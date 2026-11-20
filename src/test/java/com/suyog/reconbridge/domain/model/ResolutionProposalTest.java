package com.suyog.reconbridge.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolutionProposalTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID CASE_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final Instant PROPOSED_AT =
            Instant.parse("2026-10-29T10:00:00Z");

    private static final Instant DECIDED_AT =
            Instant.parse("2026-10-29T11:00:00Z");

    @Test
    void createsPendingProposalWithoutDecisionFields() {
        ResolutionProposal proposal = pending();

        assertEquals(TENANT_ID, proposal.tenantId());
        assertEquals(CASE_ID, proposal.caseId());
        assertEquals(
                ResolutionType.TIMING_DIFFERENCE_CONFIRMED,
                proposal.resolutionType()
        );
        assertEquals(
                ResolutionProposalStatus.PENDING,
                proposal.status()
        );
        assertEquals("analyst-subject", proposal.proposedBy());
        assertEquals(PROPOSED_AT, proposal.proposedAt());

        assertNull(proposal.decidedBy());
        assertNull(proposal.decisionComment());
        assertNull(proposal.decidedAt());
    }

    @Test
    void independentApprovalPreservesProposalIdentityAndEvidence() {
        ResolutionProposal original = pending();

        ResolutionProposal approved = original.approve(
                "approver-subject",
                "Verified the later bank statement.",
                DECIDED_AT
        );

        assertEquals(
                ResolutionProposalStatus.APPROVED,
                approved.status()
        );
        assertEquals(original.id(), approved.id());
        assertEquals(original.tenantId(), approved.tenantId());
        assertEquals(original.caseId(), approved.caseId());
        assertEquals(original.resolutionType(), approved.resolutionType());
        assertEquals(original.rationale(), approved.rationale());
        assertEquals(original.proposedBy(), approved.proposedBy());
        assertEquals(original.proposedAt(), approved.proposedAt());

        assertEquals("approver-subject", approved.decidedBy());
        assertEquals(
                "Verified the later bank statement.",
                approved.decisionComment()
        );
        assertEquals(DECIDED_AT, approved.decidedAt());

        assertEquals(
                ResolutionProposalStatus.PENDING,
                original.status()
        );
        assertNull(original.decidedBy());
    }

    @Test
    void independentRejectionRecordsReason() {
        ResolutionProposal rejected = pending().reject(
                "approver-subject",
                "The supporting statement does not identify this credit.",
                DECIDED_AT
        );

        assertEquals(
                ResolutionProposalStatus.REJECTED,
                rejected.status()
        );
        assertEquals("approver-subject", rejected.decidedBy());
        assertEquals(
                "The supporting statement does not identify this credit.",
                rejected.decisionComment()
        );
    }

    @Test
    void proposerCannotApproveOwnProposal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> pending().approve(
                        "analyst-subject",
                        "Approved.",
                        DECIDED_AT
                )
        );
    }

    @Test
    void proposerCannotRejectOwnProposal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> pending().reject(
                        "analyst-subject",
                        "Rejected.",
                        DECIDED_AT
                )
        );
    }

    @Test
    void approvedProposalCannotBeDecidedAgain() {
        ResolutionProposal approved = pending().approve(
                "approver-subject",
                "Evidence verified.",
                DECIDED_AT
        );

        assertThrows(
                IllegalStateException.class,
                () -> approved.approve(
                        "another-approver",
                        "Second approval.",
                        DECIDED_AT.plusSeconds(60)
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> approved.reject(
                        "another-approver",
                        "Attempt to reverse the decision.",
                        DECIDED_AT.plusSeconds(60)
                )
        );
    }

    @Test
    void rejectedProposalCannotBeDecidedAgain() {
        ResolutionProposal rejected = pending().reject(
                "approver-subject",
                "Insufficient supporting evidence.",
                DECIDED_AT
        );

        assertThrows(
                IllegalStateException.class,
                () -> rejected.approve(
                        "another-approver",
                        "Attempt to approve the rejected proposal.",
                        DECIDED_AT.plusSeconds(60)
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> rejected.reject(
                        "another-approver",
                        "Second rejection.",
                        DECIDED_AT.plusSeconds(60)
                )
        );
    }

    @Test
    void rejectsMissingBlankAndOversizedRationale() {
        for (String invalid : new String[]{
                null,
                "",
                "   ",
                "x".repeat(4001)
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResolutionProposal.propose(
                            TENANT_ID,
                            CASE_ID,
                            ResolutionType.ACCEPTED_VARIANCE,
                            invalid,
                            "analyst-subject",
                            PROPOSED_AT
                    )
            );
        }
    }

    @Test
    void requiresValidCommentForEitherDecision() {
        for (String invalid : new String[]{
                null,
                "",
                "   ",
                "x".repeat(4001)
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> pending().approve(
                            "approver-subject",
                            invalid,
                            DECIDED_AT
                    )
            );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> pending().reject(
                            "approver-subject",
                            invalid,
                            DECIDED_AT
                    )
            );
        }
    }

    @Test
    void rejectsDecisionBeforeProposalTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> pending().approve(
                        "approver-subject",
                        "Verified.",
                        PROPOSED_AT.minusSeconds(1)
                )
        );
    }

    @Test
    void constructorRejectsInconsistentPersistedState() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolutionProposal(
                        UUID.randomUUID(),
                        TENANT_ID,
                        CASE_ID,
                        ResolutionType.ACCEPTED_VARIANCE,
                        "Documented difference.",
                        "analyst-subject",
                        PROPOSED_AT,
                        ResolutionProposalStatus.PENDING,
                        "approver-subject",
                        "Unexpected decision on a pending proposal.",
                        DECIDED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ResolutionProposal(
                        UUID.randomUUID(),
                        TENANT_ID,
                        CASE_ID,
                        ResolutionType.ACCEPTED_VARIANCE,
                        "Documented difference.",
                        "analyst-subject",
                        PROPOSED_AT,
                        ResolutionProposalStatus.APPROVED,
                        "analyst-subject",
                        "Self-approval reconstructed from storage.",
                        DECIDED_AT
                )
        );
    }

    @Test
    void normalizesExplanationButPreservesSubjectExactly() {
        ResolutionProposal proposal = ResolutionProposal.propose(
                TENANT_ID,
                CASE_ID,
                ResolutionType.DATA_CORRECTION_CONFIRMED,
                "  Source correction verified against evidence.  ",
                "Analyst-Subject",
                PROPOSED_AT
        );

        assertEquals(
                "Source correction verified against evidence.",
                proposal.rationale()
        );
        assertEquals("Analyst-Subject", proposal.proposedBy());

        ResolutionProposal approved = proposal.approve(
                "Approver-Subject",
                "  Supporting evidence checked.  ",
                DECIDED_AT
        );

        assertEquals("Approver-Subject", approved.decidedBy());
        assertEquals(
                "Supporting evidence checked.",
                approved.decisionComment()
        );
    }

    @Test
    void resubmissionCreatesNewProposalAndPreservesRejectedOne() {
        ResolutionProposal rejected = pending().reject(
                "approver-subject",
                "Additional evidence required.",
                DECIDED_AT
        );

        ResolutionProposal resubmitted = ResolutionProposal.propose(
                TENANT_ID,
                CASE_ID,
                ResolutionType.TIMING_DIFFERENCE_CONFIRMED,
                "Additional bank advice now supports the explanation.",
                "analyst-subject",
                DECIDED_AT.plusSeconds(60)
        );

        assertNotEquals(rejected.id(), resubmitted.id());
        assertEquals(rejected.caseId(), resubmitted.caseId());

        assertEquals(
                ResolutionProposalStatus.REJECTED,
                rejected.status()
        );
        assertEquals(
                ResolutionProposalStatus.PENDING,
                resubmitted.status()
        );
    }

    @Test
    void rejectsInvalidActorSubjects() {
        for (String invalid : new String[]{
                null,
                "",
                "   ",
                "x".repeat(256)
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResolutionProposal.propose(
                            TENANT_ID,
                            CASE_ID,
                            ResolutionType.ACCEPTED_VARIANCE,
                            "Documented variance.",
                            invalid,
                            PROPOSED_AT
                    )
            );

            assertThrows(
                    IllegalArgumentException.class,
                    () -> pending().approve(
                            invalid,
                            "Evidence checked.",
                            DECIDED_AT
                    )
            );
        }
    }

    private ResolutionProposal pending() {
        return ResolutionProposal.propose(
                TENANT_ID,
                CASE_ID,
                ResolutionType.TIMING_DIFFERENCE_CONFIRMED,
                "Credit confirmed in the following bank statement.",
                "analyst-subject",
                PROPOSED_AT
        );
    }
}