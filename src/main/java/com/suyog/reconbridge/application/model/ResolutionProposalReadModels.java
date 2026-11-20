package com.suyog.reconbridge.application.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ResolutionProposalReadModels {

    private ResolutionProposalReadModels() {
    }

    public record Page<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            long totalPages
    ) {
        public Page {
            items = List.copyOf(
                    Objects.requireNonNull(items, "items must not be null")
            );

            if (page < 0 || size < 1 || size > 100) {
                throw new IllegalArgumentException(
                        "Invalid pagination parameters"
                );
            }

            if (totalElements < 0) {
                throw new IllegalArgumentException(
                        "totalElements must not be negative"
                );
            }

            long expectedPages = totalElements / size
                    + (totalElements % size == 0 ? 0 : 1);

            if (totalPages != expectedPages) {
                throw new IllegalArgumentException(
                        "Incorrect totalPages"
                );
            }
        }

        public static <T> Page<T> of(
                List<T> items,
                int page,
                int size,
                long totalElements
        ) {
            if (page < 0 || size < 1 || size > 100) {
                throw new IllegalArgumentException(
                        "Invalid pagination parameters"
                );
            }

            long totalPages = totalElements / size
                    + (totalElements % size == 0 ? 0 : 1);

            return new Page<>(
                    items,
                    page,
                    size,
                    totalElements,
                    totalPages
            );
        }
    }

    public record ProposalSummary(
            UUID id,
            UUID caseId,
            String resolutionType,
            String rationale,
            String proposedBy,
            Instant proposedAt,
            String status,
            String decidedBy,
            String decisionComment,
            Instant decidedAt
    ) {
    }

    public record PendingApproval(
            UUID proposalId,
            UUID caseId,
            UUID runId,
            String itemType,
            UUID evidenceId,
            String reason,
            String resolutionType,
            String rationale,
            String proposedBy,
            Instant proposedAt,
            String assignedTo,
            long caseVersion
    ) {
    }
}