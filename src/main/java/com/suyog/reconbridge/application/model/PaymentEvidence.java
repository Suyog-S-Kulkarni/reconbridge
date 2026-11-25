package com.suyog.reconbridge.application.model;

import java.util.List;
import java.util.UUID;

public final class PaymentEvidence {

    private PaymentEvidence() {
    }

    public record Item(
            UUID paymentId,
            String provider,
            String paymentReference,
            long amountMinor,
            String currency
    ) {
    }

    public record Page(
            List<Item> items,
            int page,
            int size,
            long totalElements,
            long totalPages
    ) {
        public Page {
            items = List.copyOf(items);
        }

        public static Page of(
                List<Item> items,
                int page,
                int size,
                long totalElements
        ) {
            long totalPages = totalElements / size;

            if (totalElements % size != 0) {
                totalPages++;
            }

            return new Page(
                    items,
                    page,
                    size,
                    totalElements,
                    totalPages
            );
        }
    }
}