package com.suyog.reconbridge.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StoredReconciliationRun(
        UUID id,
        String ruleVersion,
        int reportVersion,
        String createdBy,
        Instant createdAt,
        ReconciliationReportSnapshot report
) {
    public StoredReconciliationRun {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        Objects.requireNonNull(report, "report must not be null");

        if (ruleVersion == null
                || ruleVersion.isBlank()
                || ruleVersion.length() > 100) {
            throw new IllegalArgumentException(
                    "A rule version of 1 to 100 characters is required."
            );
        }

        if (createdBy == null
                || createdBy.isBlank()
                || createdBy.length() > 255) {
            throw new IllegalArgumentException(
                    "A valid creator subject is required."
            );
        }

        boolean compatible = switch (reportVersion) {
            case 1 -> report instanceof BankReconciliationPreview;
            case 2 -> report instanceof ThreeSourceReconciliationPreview;
            default -> false;
        };

        if (!compatible) {
            throw new IllegalArgumentException(
                    "Report type does not match supported report version: "
                            + reportVersion
            );
        }
    }
}