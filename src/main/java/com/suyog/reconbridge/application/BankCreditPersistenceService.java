package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.BankCreditWriteRepository;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class BankCreditPersistenceService {

    private final ImportBatchRepository imports;
    private final BankCreditWriteRepository credits;

    public BankCreditPersistenceService(
            ImportBatchRepository imports,
            BankCreditWriteRepository credits
    ) {
        this.imports = imports;
        this.credits = credits;
    }

    @Transactional
    public ImportBatchSnapshot complete(
            UUID tenantId,
            UUID importId,
            long expectedVersion,
            List<BankCredit> bankCredits
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(importId, "importId must not be null");
        Objects.requireNonNull(bankCredits, "bankCredits must not be null");

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

        if (imported.getSourceType() != SourceType.BANK_STATEMENT) {
            throw new IllegalStateException(
                    "Bank credit persistence requires a BANK_STATEMENT import."
            );
        }

        if (imported.getStatus() != ImportStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Import must be PROCESSING before bank credits are saved."
            );
        }

        List<BankCredit> validatedCredits = List.copyOf(bankCredits);

        validateCredits(tenantId, validatedCredits);

        /*
         * Flush the versioned update before inserting evidence.
         *
         * If two transactions loaded the same version, only one can
         * successfully update the import using that version.
         *
         * This is a flush, not a commit. A later insert failure rolls
         * back this state change as well.
         */
        imported.markReady(validatedCredits.size());

        ImportBatchEntity saved = imports.saveAndFlush(imported);

        for (BankCredit credit : validatedCredits) {
            credits.insert(
                    tenantId,
                    importId,
                    credit
            );
        }

        return snapshot(saved);
    }

    private void validateCredits(
            UUID tenantId,
            List<BankCredit> bankCredits
    ) {
        if (bankCredits.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one bank credit is required."
            );
        }

        Set<UUID> identifiers = new HashSet<>();

        for (BankCredit credit : bankCredits) {
            if (!tenantId.equals(credit.settlementKey().tenantId())) {
                throw new IllegalArgumentException(
                        "Every bank credit must belong to the import tenant."
                );
            }

            if (!identifiers.add(credit.id())) {
                throw new IllegalArgumentException(
                        "Duplicate bank credit ID in completion request."
                );
            }

            requireLength(
                    credit.settlementKey().provider(),
                    "provider",
                    100
            );

            requireLength(
                    credit.settlementKey().bankAccountReference(),
                    "bankAccountReference",
                    255
            );

            requireLength(
                    credit.settlementKey().settlementReference(),
                    "settlementReference",
                    255
            );
        }
    }

    private void requireLength(
            String value,
            String field,
            int maximumLength
    ) {
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must not exceed " + maximumLength + " characters."
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