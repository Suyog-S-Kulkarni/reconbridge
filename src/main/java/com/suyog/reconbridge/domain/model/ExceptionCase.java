package com.suyog.reconbridge.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExceptionCase(
        UUID id,
        UUID tenantId,
        CaseEvidenceReference evidence,
        ExceptionCaseStatus status,
        String createdBy,
        String assignedTo,
        Instant createdAt,
        Instant updatedAt
) {

    public ExceptionCase {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        requireSubject(createdBy);

        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "updatedAt must not precede createdAt."
            );
        }

        if (status == ExceptionCaseStatus.OPEN) {
            if (assignedTo != null) {
                throw new IllegalArgumentException(
                        "An OPEN case must not have an assignee."
                );
            }
        } else {
            requireSubject(assignedTo);
        }
    }

    public static ExceptionCase open(
            UUID tenantId,
            CaseEvidenceReference evidence,
            String actor,
            Instant now
    ) {
        return new ExceptionCase(
                UUID.randomUUID(),
                tenantId,
                evidence,
                ExceptionCaseStatus.OPEN,
                actor,
                null,
                now,
                now
        );
    }

    public ExceptionCase claim(String actor, Instant now) {
        requireSubject(actor);
        requireTime(now);
        requireStatus(ExceptionCaseStatus.OPEN);

        return new ExceptionCase(
                id,
                tenantId,
                evidence,
                ExceptionCaseStatus.IN_REVIEW,
                createdBy,
                actor,
                createdAt,
                now
        );
    }

    public InvestigationNote addNote(
            String actor,
            String text,
            Instant now
    ) {
        requireSubject(actor);
        requireTime(now);
        requireStatus(ExceptionCaseStatus.IN_REVIEW);
        requireAssignee(actor);

        return new InvestigationNote(
                UUID.randomUUID(),
                tenantId,
                id,
                actor,
                text,
                now
        );
    }

    public ExceptionCase awaitApproval(
            String actor,
            Instant now
    ) {
        requireSubject(actor);
        requireTime(now);
        requireStatus(ExceptionCaseStatus.IN_REVIEW);
        requireAssignee(actor);

        return withStatus(
                ExceptionCaseStatus.AWAITING_APPROVAL,
                now
        );
    }

    public ExceptionCase resolve(Instant now) {
        requireTime(now);
        requireStatus(ExceptionCaseStatus.AWAITING_APPROVAL);

        return withStatus(ExceptionCaseStatus.RESOLVED, now);
    }

    public ExceptionCase resumeInvestigation(Instant now) {
        requireTime(now);
        requireStatus(ExceptionCaseStatus.AWAITING_APPROVAL);

        return withStatus(ExceptionCaseStatus.IN_REVIEW, now);
    }

    private ExceptionCase withStatus(
            ExceptionCaseStatus nextStatus,
            Instant now
    ) {
        return new ExceptionCase(
                id,
                tenantId,
                evidence,
                nextStatus,
                createdBy,
                assignedTo,
                createdAt,
                now
        );
    }

    private void requireAssignee(String actor) {
        if (!actor.equals(assignedTo)) {
            throw new IllegalStateException(
                    "Only the assigned analyst can perform this operation."
            );
        }
    }

    private void requireStatus(ExceptionCaseStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Case must be " + expected
                            + " for this operation, but is " + status + "."
            );
        }
    }

    private void requireTime(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "The operation time must not precede updatedAt."
            );
        }
    }

    private static void requireSubject(String subject) {
        if (subject == null
                || subject.isBlank()
                || subject.length() > 255) {
            throw new IllegalArgumentException(
                    "A subject of 1 to 255 nonblank characters is required."
            );
        }
    }
}