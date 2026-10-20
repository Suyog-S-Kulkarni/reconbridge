package com.suyog.reconbridge.domain.model;

import java.util.Objects;
import java.util.UUID;

public record MatchKey(
        UUID tenantId,
        String provider,
        String paymentReference
) {

    public MatchKey {
        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null"
        );

        provider = requireText(provider, "provider");
        paymentReference = requireText(
                paymentReference,
                "paymentReference"
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be blank"
            );
        }

        return value.trim();
    }
}