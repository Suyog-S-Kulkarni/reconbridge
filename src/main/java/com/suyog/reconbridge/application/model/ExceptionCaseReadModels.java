package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ExceptionCaseReadModels {

    private ExceptionCaseReadModels() {
    }

    public record Page<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            long totalPages
    ) {
        public Page {
            items = List.copyOf(items);
        }

        public static <T> Page<T> of(
                List<T> items,
                int page,
                int size,
                long totalElements
        ) {
            long totalPages = totalElements / size;

            if (totalElements % size != 0) {
                totalPages++;
            }

            return new Page<>(
                    items,
                    page,
                    size,
                    totalElements,
                    totalPages
            );
        }
    }

    public record CaseSummary(
            UUID id,
            UUID runId,
            String itemType,
            UUID evidenceId,
            String reason,
            ExceptionCaseStatus status,
            String createdBy,
            String assignedTo,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    public record NoteSummary(
            UUID id,
            UUID caseId,
            String authorSubject,
            String text,
            Instant createdAt
    ) {
    }
}