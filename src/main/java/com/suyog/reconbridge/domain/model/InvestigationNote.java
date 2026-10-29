package com.suyog.reconbridge.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InvestigationNote(
        UUID id,
        UUID tenantId,
        UUID caseId,
        String authorSubject,
        String text,
        Instant createdAt
) {

    public static final int MAX_TEXT_LENGTH = 4000;

    public InvestigationNote {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (authorSubject == null
                || authorSubject.isBlank()
                || authorSubject.length() > 255) {
            throw new IllegalArgumentException(
                    "A valid author subject is required."
            );
        }

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Investigation note text must not be blank."
            );
        }

        text = text.strip();

        if (text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "Investigation note text must not exceed "
                            + MAX_TEXT_LENGTH + " characters."
            );
        }
    }
}