package com.suyog.reconbridge.domain.model;

import java.util.Objects;
import java.util.UUID;

public record SettlementKey(
        UUID tenantId,
        String provider,
        String bankAccountReference,
        String settlementReference
) {

    public SettlementKey {
        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        provider = requireText(provider, "provider");

        bankAccountReference = requireText(
                bankAccountReference,
                "bankAccountReference"
        );

        settlementReference = requireText(
                settlementReference,
                "settlementReference"
        );
    }

    private static String requireText(
            String value,
            String fieldName
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        return value.trim();
    }
}