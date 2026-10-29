package com.suyog.reconbridge.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExceptionCaseTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-4111-8111-111111111111"
            );

    private static final UUID RUN_ID =
            UUID.fromString(
                    "22222222-2222-4222-8222-222222222222"
            );

    private static final UUID BATCH_ID =
            UUID.fromString(
                    "33333333-3333-4333-8333-333333333333"
            );

    private static final UUID CREDIT_ID =
            UUID.fromString(
                    "44444444-4444-4444-8444-444444444444"
            );

    private static final Instant OPENED_AT =
            Instant.parse("2026-10-28T10:00:00Z");

    private static final Instant CLAIMED_AT =
            Instant.parse("2026-10-28T10:05:00Z");

    private static final Instant NOTED_AT =
            Instant.parse("2026-10-28T10:10:00Z");

    @Test
    void opensUnassignedCaseWithEvidenceAndCreator() {
        ExceptionCase result = openCase();

        assertNotNull(result.id());
        assertEquals(TENANT_ID, result.tenantId());
        assertEquals(RUN_ID, result.evidence().runId());
        assertEquals(BATCH_ID, result.evidence().evidenceId());
        assertEquals(
                CaseEvidenceReference.Reason.AMOUNT_MISMATCH,
                result.evidence().reason()
        );
        assertEquals(ExceptionCaseStatus.OPEN, result.status());
        assertEquals("creator-subject", result.createdBy());
        assertNull(result.assignedTo());
        assertEquals(OPENED_AT, result.createdAt());
        assertEquals(OPENED_AT, result.updatedAt());
    }

    @Test
    void rejectsMatchedBatchEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CaseEvidenceReference.forBatch(
                        RUN_ID,
                        BATCH_ID,
                        MatchOutcome.MATCHED
                )
        );
    }

    @Test
    void acceptsEveryUnresolvedBatchOutcome() {
        for (MatchOutcome outcome : List.of(
                MatchOutcome.MISSING_COUNTERPART,
                MatchOutcome.AMBIGUOUS,
                MatchOutcome.AMOUNT_MISMATCH,
                MatchOutcome.CURRENCY_MISMATCH
        )) {
            CaseEvidenceReference reference =
                    CaseEvidenceReference.forBatch(
                            RUN_ID,
                            BATCH_ID,
                            outcome
                    );

            assertEquals(
                    CaseEvidenceReference.ItemType.BATCH,
                    reference.itemType()
            );
            assertEquals(
                    outcome.name(),
                    reference.reason().name()
            );
        }
    }

    @Test
    void opensCaseForOrphanBankCredit() {
        CaseEvidenceReference reference =
                CaseEvidenceReference.forOrphan(
                        RUN_ID,
                        CREDIT_ID
                );

        ExceptionCase result = ExceptionCase.open(
                TENANT_ID,
                reference,
                "creator-subject",
                OPENED_AT
        );

        assertEquals(
                CaseEvidenceReference.ItemType.ORPHAN,
                result.evidence().itemType()
        );
        assertEquals(
                CaseEvidenceReference.Reason.ORPHAN_BANK_CREDIT,
                result.evidence().reason()
        );
        assertEquals(
                CREDIT_ID,
                result.evidence().evidenceId()
        );
    }

    @Test
    void rejectsIncompatibleEvidenceTypeAndReason() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CaseEvidenceReference(
                        RUN_ID,
                        CaseEvidenceReference.ItemType.ORPHAN,
                        CREDIT_ID,
                        CaseEvidenceReference.Reason.AMOUNT_MISMATCH
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new CaseEvidenceReference(
                        RUN_ID,
                        CaseEvidenceReference.ItemType.BATCH,
                        BATCH_ID,
                        CaseEvidenceReference.Reason.ORPHAN_BANK_CREDIT
                )
        );
    }

    @Test
    void claimsCaseAndPreservesOriginalIdentity() {
        ExceptionCase original = openCase();

        ExceptionCase claimed = original.claim(
                "analyst-subject",
                CLAIMED_AT
        );

        assertEquals(original.id(), claimed.id());
        assertEquals(original.tenantId(), claimed.tenantId());
        assertEquals(original.evidence(), claimed.evidence());
        assertEquals(original.createdBy(), claimed.createdBy());
        assertEquals(original.createdAt(), claimed.createdAt());

        assertEquals(
                ExceptionCaseStatus.IN_REVIEW,
                claimed.status()
        );
        assertEquals("analyst-subject", claimed.assignedTo());
        assertEquals(CLAIMED_AT, claimed.updatedAt());

        // The original value remains unchanged.
        assertEquals(ExceptionCaseStatus.OPEN, original.status());
        assertNull(original.assignedTo());
    }

    @Test
    void rejectsClaimingAnAlreadyClaimedCase() {
        ExceptionCase claimed = claimedCase();

        assertThrows(
                IllegalStateException.class,
                () -> claimed.claim(
                        "another-analyst",
                        NOTED_AT
                )
        );
    }

    @Test
    void rejectsClaimWithBlankSubject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> openCase().claim("   ", CLAIMED_AT)
        );
    }

    @Test
    void rejectsOperationBeforeCurrentCaseTimestamp() {
        assertThrows(
                IllegalArgumentException.class,
                () -> openCase().claim(
                        "analyst-subject",
                        OPENED_AT.minusSeconds(1)
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> claimedCase().addNote(
                        "analyst-subject",
                        "Investigating the discrepancy.",
                        OPENED_AT
                )
        );
    }

    @Test
    void assignedAnalystCanAddNote() {
        ExceptionCase claimed = claimedCase();

        InvestigationNote note = claimed.addNote(
                "analyst-subject",
                "  Requested the processor settlement advice.  ",
                NOTED_AT
        );

        assertNotNull(note.id());
        assertEquals(TENANT_ID, note.tenantId());
        assertEquals(claimed.id(), note.caseId());
        assertEquals("analyst-subject", note.authorSubject());
        assertEquals(
                "Requested the processor settlement advice.",
                note.text()
        );
        assertEquals(NOTED_AT, note.createdAt());

        // Creating a note does not change the evidence or case state.
        assertEquals(
                ExceptionCaseStatus.IN_REVIEW,
                claimed.status()
        );
        assertEquals(CLAIMED_AT, claimed.updatedAt());
    }

    @Test
    void openCaseCannotReceiveInvestigationNote() {
        assertThrows(
                IllegalStateException.class,
                () -> openCase().addNote(
                        "analyst-subject",
                        "Investigating.",
                        NOTED_AT
                )
        );
    }

    @Test
    void anotherAnalystCannotAddNoteToClaimedCase() {
        assertThrows(
                IllegalStateException.class,
                () -> claimedCase().addNote(
                        "another-analyst",
                        "Investigating.",
                        NOTED_AT
                )
        );
    }

    @Test
    void rejectsBlankAndOversizedNotes() {
        ExceptionCase claimed = claimedCase();

        assertThrows(
                IllegalArgumentException.class,
                () -> claimed.addNote(
                        "analyst-subject",
                        "   ",
                        NOTED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> claimed.addNote(
                        "analyst-subject",
                        "x".repeat(
                                InvestigationNote.MAX_TEXT_LENGTH + 1
                        ),
                        NOTED_AT
                )
        );
    }

    @Test
    void acceptsNoteAtMaximumLength() {
        InvestigationNote note = claimedCase().addNote(
                "analyst-subject",
                "x".repeat(InvestigationNote.MAX_TEXT_LENGTH),
                NOTED_AT
        );

        assertEquals(
                InvestigationNote.MAX_TEXT_LENGTH,
                note.text().length()
        );
    }

    @Test
    void rejectsInvalidRestoredCaseState() {
        ExceptionCase original = openCase();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExceptionCase(
                        original.id(),
                        TENANT_ID,
                        original.evidence(),
                        ExceptionCaseStatus.OPEN,
                        "creator-subject",
                        "unexpected-assignee",
                        OPENED_AT,
                        OPENED_AT
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExceptionCase(
                        original.id(),
                        TENANT_ID,
                        original.evidence(),
                        ExceptionCaseStatus.IN_REVIEW,
                        "creator-subject",
                        null,
                        OPENED_AT,
                        CLAIMED_AT
                )
        );
    }

    private ExceptionCase openCase() {
        return ExceptionCase.open(
                TENANT_ID,
                CaseEvidenceReference.forBatch(
                        RUN_ID,
                        BATCH_ID,
                        MatchOutcome.AMOUNT_MISMATCH
                ),
                "creator-subject",
                OPENED_AT
        );
    }

    private ExceptionCase claimedCase() {
        return openCase().claim(
                "analyst-subject",
                CLAIMED_AT
        );
    }
}