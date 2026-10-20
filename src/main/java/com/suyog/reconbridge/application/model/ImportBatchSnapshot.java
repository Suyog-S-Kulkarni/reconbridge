package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;

import java.time.LocalDate;
import java.util.UUID;

public record ImportBatchSnapshot(
        UUID id,
        UUID tenantId,
        SourceType sourceType,
        String sourceSystem,
        ImportStatus status,
        long rowCount,
        LocalDate businessDate,
        long version
) {
}