package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.PaymentEvidenceException;
import com.suyog.reconbridge.application.model.PaymentEvidence;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.InternalPaymentReadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
)
public class PaymentEvidenceService {

    private final ImportBatchRepository imports;
    private final InternalPaymentReadRepository payments;

    public PaymentEvidenceService(
            ImportBatchRepository imports,
            InternalPaymentReadRepository payments
    ) {
        this.imports = imports;
        this.payments = payments;
    }

    public PaymentEvidence.Page payments(
            UUID tenantId,
            UUID importId,
            int page,
            int size
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(importId, "importId must not be null");

        validatePagination(page, size);

        ImportBatchEntity imported = imports
                .findByTenantIdAndId(tenantId, importId)
                .orElseThrow(
                        () -> new ImportNotFoundException(importId)
                );

        if (imported.getSourceType() != SourceType.INTERNAL_PAYMENT) {
            throw new PaymentEvidenceException(
                    409,
                    "UNSUPPORTED_SOURCE",
                    "Payment evidence requires an INTERNAL_PAYMENT import."
            );
        }

        if (imported.getStatus() != ImportStatus.READY) {
            throw new PaymentEvidenceException(
                    409,
                    "IMPORT_NOT_READY",
                    "Payment evidence is available only for READY imports."
            );
        }

        long total = payments.count(tenantId, importId);

        /*
         * Successful payment completion requires at least one
         * payment and records that count on the import.
         */
        if (total == 0 || total != imported.getRowCount()) {
            throw new PaymentEvidenceException(
                    409,
                    "PAYMENT_EVIDENCE_INCONSISTENT",
                    "Persisted payment evidence does not match "
                            + "the READY import's recorded row count."
            );
        }

        long offset = (long) page * size;

        List<PaymentEvidence.Item> items = payments.findPage(
                tenantId,
                importId,
                size,
                offset
        );

        return PaymentEvidence.Page.of(
                items,
                page,
                size,
                total
        );
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new PaymentEvidenceException(
                    400,
                    "INVALID_PAGINATION",
                    "page must be zero or greater; "
                            + "size must be between 1 and 100."
            );
        }
    }
}