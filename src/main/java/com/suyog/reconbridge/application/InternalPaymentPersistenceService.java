package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.InternalPaymentWriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class InternalPaymentPersistenceService {

    private static final int MAX_PAYMENTS = 100_000;

    private final ImportBatchRepository imports;
    private final InternalPaymentWriteRepository payments;

    public InternalPaymentPersistenceService(
            ImportBatchRepository imports,
            InternalPaymentWriteRepository payments
    ) {
        this.imports = imports;
        this.payments = payments;
    }

    @Transactional
    public ImportBatchSnapshot complete(
            UUID tenantId,
            UUID importId,
            long expectedVersion,
            List<PaymentRecord> suppliedPayments
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(importId, "importId must not be null");
        Objects.requireNonNull(
                suppliedPayments,
                "suppliedPayments must not be null"
        );

        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "expectedVersion must not be negative"
            );
        }

        ImportBatchEntity imported = imports
                .findByTenantIdAndId(tenantId, importId)
                .orElseThrow(
                        () -> new ImportNotFoundException(importId)
                );

        long actualVersion = imported.getVersion();

        if (actualVersion != expectedVersion) {
            throw new StaleImportVersionException(
                    importId,
                    expectedVersion,
                    actualVersion
            );
        }

        if (imported.getSourceType() != SourceType.INTERNAL_PAYMENT) {
            throw new IllegalStateException(
                    "Payment persistence requires an INTERNAL_PAYMENT import."
            );
        }

        if (imported.getStatus() != ImportStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Import must be PROCESSING before payments are saved."
            );
        }

        if (suppliedPayments.isEmpty()
                || suppliedPayments.size() > MAX_PAYMENTS) {
            throw new IllegalArgumentException(
                    "Completion requires between 1 and "
                            + MAX_PAYMENTS
                            + " payments."
            );
        }

        List<PaymentRecord> validatedPayments =
                List.copyOf(suppliedPayments);

        validatePayments(imported, validatedPayments);

        /*
         * Flush the optimistic update before writing evidence.
         *
         * Concurrent completions must compete on the import version.
         * This flush is not a commit: a later insert failure rolls
         * back the READY transition and all payment inserts.
         */
        imported.markReady(validatedPayments.size());

        ImportBatchEntity saved = imports.saveAndFlush(imported);

        for (PaymentRecord payment : validatedPayments) {
            payments.insert(
                    tenantId,
                    importId,
                    payment
            );
        }

        return snapshot(saved);
    }

    private void validatePayments(
            ImportBatchEntity imported,
            List<PaymentRecord> suppliedPayments
    ) {
        Set<UUID> identifiers = new HashSet<>();
        Set<MatchKey> matchingKeys = new HashSet<>();

        for (PaymentRecord payment : suppliedPayments) {
            MatchKey key = payment.matchKey();

            if (!imported.getTenantId().equals(key.tenantId())) {
                throw new IllegalArgumentException(
                        "Every payment must belong to the import tenant."
                );
            }

            if (!imported.getSourceSystem().equals(key.provider())) {
                throw new IllegalArgumentException(
                        "Payment provider must match the import sourceSystem."
                );
            }

            requireText(key.provider(), "provider", 100);
            requireText(
                    key.paymentReference(),
                    "paymentReference",
                    255
            );

            if (!identifiers.add(payment.id())) {
                throw new IllegalArgumentException(
                        "Duplicate payment ID in completion request."
                );
            }

            if (!matchingKeys.add(key)) {
                throw new IllegalArgumentException(
                        "Duplicate payment matching key in completion request."
                );
            }

            if (payment.amount().amountMinor() <= 0) {
                throw new IllegalArgumentException(
                        "Payment amount must be greater than zero."
                );
            }

            String currency = payment.amount()
                    .currency()
                    .getCurrencyCode();

            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(
                        "Payment currency must contain three uppercase letters."
                );
            }
        }
    }

    private void requireText(
            String value,
            String field,
            int maximumLength
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank."
            );
        }

        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field
                            + " must not exceed "
                            + maximumLength
                            + " characters."
            );
        }

        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " must not contain control characters."
            );
        }
    }

    private ImportBatchSnapshot snapshot(ImportBatchEntity imported) {
        return new ImportBatchSnapshot(
                imported.getId(),
                imported.getTenantId(),
                imported.getSourceType(),
                imported.getSourceSystem(),
                imported.getStatus(),
                imported.getRowCount(),
                imported.getBusinessDate(),
                imported.getVersion()
        );
    }
}