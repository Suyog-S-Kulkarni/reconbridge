package com.suyog.reconbridge.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ResolutionProposal(
        UUID id,
        UUID tenantId,
        UUID caseId,
        ResolutionType resolutionType,
        String rationale,
        String proposedBy,
        Instant proposedAt,
        ResolutionProposalStatus status,
        String decidedBy,
        String decisionComment,
        Instant decidedAt
) {

    public static final int MAX_SUBJECT_LENGTH = 255;
    public static final int MAX_TEXT_LENGTH = 4000;

    public ResolutionProposal {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(
                resolutionType,
                "resolutionType must not be null"
        );
        Objects.requireNonNull(
                proposedAt,
                "proposedAt must not be null"
        );
        Objects.requireNonNull(status, "status must not be null");

        rationale = requireText(rationale, "rationale");
        proposedBy = requireSubject(proposedBy, "proposedBy");

        if (status == ResolutionProposalStatus.PENDING) {
            if (decidedBy != null
                    || decisionComment != null
                    || decidedAt != null) {
                throw new IllegalArgumentException(
                        "A pending proposal must not contain a decision."
                );
            }
        } else {
            decidedBy = requireSubject(decidedBy, "decidedBy");
            decisionComment = requireText(
                    decisionComment,
                    "decisionComment"
            );

            Objects.requireNonNull(
                    decidedAt,
                    "decidedAt must not be null for a decided proposal"
            );

            if (proposedBy.equals(decidedBy)) {
                throw new IllegalArgumentException(
                        "The proposer cannot decide their own proposal."
                );
            }

            if (decidedAt.isBefore(proposedAt)) {
                throw new IllegalArgumentException(
                        "Decision time must not precede proposal time."
                );
            }
        }
    }

    public static ResolutionProposal propose(
            UUID tenantId,
            UUID caseId,
            ResolutionType resolutionType,
            String rationale,
            String proposedBy,
            Instant proposedAt
    ) {
        return new ResolutionProposal(
                UUID.randomUUID(),
                tenantId,
                caseId,
                resolutionType,
                rationale,
                proposedBy,
                proposedAt,
                ResolutionProposalStatus.PENDING,
                null,
                null,
                null
        );
    }

    public ResolutionProposal approve(
            String approverSubject,
            String comment,
            Instant decisionTime
    ) {
        return decide(
                ResolutionProposalStatus.APPROVED,
                approverSubject,
                comment,
                decisionTime
        );
    }

    public ResolutionProposal reject(
            String approverSubject,
            String comment,
            Instant decisionTime
    ) {
        return decide(
                ResolutionProposalStatus.REJECTED,
                approverSubject,
                comment,
                decisionTime
        );
    }

    private ResolutionProposal decide(
            ResolutionProposalStatus decision,
            String approverSubject,
            String comment,
            Instant decisionTime
    ) {
        if (status != ResolutionProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Only a pending proposal can be decided."
            );
        }

        return new ResolutionProposal(
                id,
                tenantId,
                caseId,
                resolutionType,
                rationale,
                proposedBy,
                proposedAt,
                decision,
                approverSubject,
                comment,
                decisionTime
        );
    }

    private static String requireSubject(
            String value,
            String fieldName
    ) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must contain 1 to "
                            + MAX_SUBJECT_LENGTH
                            + " characters."
            );
        }

        // Preserve the exact authenticated subject.
        // Identity values must not be trimmed or case-normalized.
        return value;
    }

    private static String requireText(
            String value,
            String fieldName
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null."
            );
        }

        String normalized = value.strip();

        if (normalized.isBlank()
                || normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must contain 1 to "
                            + MAX_TEXT_LENGTH
                            + " characters."
            );
        }

        return normalized;
    }
}