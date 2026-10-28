package com.suyog.reconbridge.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateReconciliationRunRequest(
        @NotNull UUID settlementImportId,
        @NotNull UUID bankImportId
) {
}