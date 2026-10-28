package com.suyog.reconbridge.application.model;

public record RunCreationResult(
        StoredReconciliationRun run,
        boolean created
) {
}