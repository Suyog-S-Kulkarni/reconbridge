package com.suyog.reconbridge.domain.model;

import java.util.Objects;
import java.util.UUID;

public record CaseEvidenceReference(
        UUID runId,
        ItemType itemType,
        UUID evidenceId,
        Reason reason
) {

    public CaseEvidenceReference {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(itemType, "itemType must not be null");
        Objects.requireNonNull(evidenceId, "evidenceId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (itemType == ItemType.ORPHAN
                && reason != Reason.ORPHAN_BANK_CREDIT) {
            throw new IllegalArgumentException(
                    "An orphan item requires ORPHAN_BANK_CREDIT."
            );
        }

        if (itemType == ItemType.BATCH
                && reason == Reason.ORPHAN_BANK_CREDIT) {
            throw new IllegalArgumentException(
                    "A batch item cannot use ORPHAN_BANK_CREDIT."
            );
        }
    }

    public static CaseEvidenceReference forBatch(
            UUID runId,
            UUID batchId,
            MatchOutcome outcome
    ) {
        Objects.requireNonNull(outcome, "outcome must not be null");

        Reason reason = switch (outcome) {
            case MISSING_COUNTERPART -> Reason.MISSING_COUNTERPART;
            case AMBIGUOUS -> Reason.AMBIGUOUS;
            case AMOUNT_MISMATCH -> Reason.AMOUNT_MISMATCH;
            case CURRENCY_MISMATCH -> Reason.CURRENCY_MISMATCH;
            case MATCHED -> throw new IllegalArgumentException(
                    "A matched result cannot open an exception case."
            );
        };

        return new CaseEvidenceReference(
                runId,
                ItemType.BATCH,
                batchId,
                reason
        );
    }

    public static CaseEvidenceReference forOrphan(
            UUID runId,
            UUID creditId
    ) {
        return new CaseEvidenceReference(
                runId,
                ItemType.ORPHAN,
                creditId,
                Reason.ORPHAN_BANK_CREDIT
        );
    }

    public enum ItemType {
        BATCH,
        ORPHAN
    }

    public enum Reason {
        MISSING_COUNTERPART,
        AMBIGUOUS,
        AMOUNT_MISMATCH,
        CURRENCY_MISMATCH,
        ORPHAN_BANK_CREDIT
    }
}