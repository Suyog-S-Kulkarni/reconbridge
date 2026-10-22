package com.suyog.reconbridge.application.model;

import java.util.List;
import java.util.UUID;

public final class SettlementEvidence {

    private SettlementEvidence() {
    }

    public record Batch(
            UUID batchId,
            String provider,
            String settlementReference,
            String bankAccountReference,
            long declaredNetMinor,
            String currency
    ) {
    }

    public record Line(
            UUID lineId,
            UUID batchId,
            String paymentReference,
            long grossMinor,
            long feeMinor,
            long netMinor,
            String currency
    ) {
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
    }
}