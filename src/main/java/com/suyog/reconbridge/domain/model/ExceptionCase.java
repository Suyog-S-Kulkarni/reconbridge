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

        if (status == ExceptionCaseStatus.OPEN && assignedTo != null) {
            throw new IllegalArgumentException(
                    "An OPEN case must not have an assignee."
            );
        }

        if (status == ExceptionCaseStatus.IN_REVIEW) {
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

    public ExceptionCase claim(
            String actor,
            Instant now
    ) {
        requireSubject(actor);
        requireTime(now);

        if (status != ExceptionCaseStatus.OPEN) {
            throw new IllegalStateException(
                    "Only an OPEN case can be claimed."
            );
        }

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

        if (status != ExceptionCaseStatus.IN_REVIEW) {
            throw new IllegalStateException(
                    "Claim the case before adding investigation notes."
            );
        }

        if (!assignedTo.equals(actor)) {
            throw new IllegalStateException(
                    "Only the assigned analyst can add investigation notes."
            );
        }

        return new InvestigationNote(
                UUID.randomUUID(),
                tenantId,
                id,
                actor,
                text,
                now
        );
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