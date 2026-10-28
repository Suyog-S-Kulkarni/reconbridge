package com.suyog.reconbridge.application.model;

import java.time.Instant;
import java.util.UUID;

public record StoredReconciliationRun(
        UUID id,
        String ruleVersion,
        int reportVersion,
        String createdBy,
        Instant createdAt,
        BankReconciliationPreview report
) {
}