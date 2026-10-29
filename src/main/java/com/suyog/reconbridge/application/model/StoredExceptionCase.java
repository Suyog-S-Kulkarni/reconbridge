package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.ExceptionCase;

import java.util.Objects;

public record StoredExceptionCase(
        ExceptionCase details,
        long version
) {

    public StoredExceptionCase {
        Objects.requireNonNull(details, "details must not be null");

        if (version < 0) {
            throw new IllegalArgumentException(
                    "version must not be negative"
            );
        }
    }
}