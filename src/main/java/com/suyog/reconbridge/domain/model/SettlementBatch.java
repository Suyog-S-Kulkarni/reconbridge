package com.suyog.reconbridge.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record SettlementBatch(
        UUID id,
        SettlementKey settlementKey,
        List<SettlementLine> lines,
        Money declaredNetAmount
) {

    public SettlementBatch {
        Objects.requireNonNull(id, "id must not be null");

        Objects.requireNonNull(
                settlementKey,
                "settlementKey must not be null"
        );

        Objects.requireNonNull(
                lines,
                "lines must not be null"
        );

        Objects.requireNonNull(
                declaredNetAmount,
                "declaredNetAmount must not be null"
        );

        lines = List.copyOf(lines);

        if (lines.isEmpty()) {
            throw new IllegalArgumentException(
                    "Settlement batch must contain at least one line"
            );
        }

        if (declaredNetAmount.amountMinor() <= 0) {
            throw new IllegalArgumentException(
                    "This bank-credit reconciliation pass requires "
                            + "a positive declared net amount"
            );
        }

        Set<UUID> lineIds = new HashSet<>();

        Money calculatedNet = new Money(
                0,
                declaredNetAmount.currency()
        );

        for (SettlementLine line : lines) {
            if (!lineIds.add(line.id())) {
                throw new IllegalArgumentException(
                        "Duplicate settlement line ID: " + line.id()
                );
            }

            if (!settlementKey.tenantId()
                    .equals(line.matchKey().tenantId())) {
                throw new IllegalArgumentException(
                        "Settlement line belongs to a different tenant"
                );
            }

            if (!settlementKey.provider()
                    .equals(line.matchKey().provider())) {
                throw new IllegalArgumentException(
                        "Settlement line belongs to a different provider"
                );
            }

            calculatedNet = calculatedNet.add(line.netAmount());
        }

        if (!calculatedNet.equals(declaredNetAmount)) {
            throw new IllegalArgumentException(
                    "Declared net amount must equal the sum "
                            + "of settlement line net amounts"
            );
        }
    }
}