package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.BankPreviewException;
import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.model.BankReconciliationPreview;
import com.suyog.reconbridge.application.model.PaymentEvidence;
import com.suyog.reconbridge.application.model.ThreeSourceReconciliationPreview;
import com.suyog.reconbridge.domain.matching.ExactReferenceMatchingStrategy;
import com.suyog.reconbridge.domain.matching.ExactSettlementBankMatchingStrategy;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.BankMatchResult;
import com.suyog.reconbridge.domain.model.CombinedReconciliationReport;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.MatchResult;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementLine;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.domain.service.CombinedReconciliationService;
import com.suyog.reconbridge.domain.service.PaymentReconciliationService;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.repository.BankPreviewReadRepository;
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
public class ThreeSourceReconciliationPreviewService {

    private static final int MAX_PAYMENTS = 10_000;

    private final ImportBatchRepository imports;
    private final InternalPaymentReadRepository payments;
    private final BankPreviewReadRepository evidence;

    private final CombinedReconciliationService reconciliation =
            new CombinedReconciliationService(
                    new PaymentReconciliationService(
                            new ExactReferenceMatchingStrategy()
                    ),
                    new ExactSettlementBankMatchingStrategy()
            );

    public ThreeSourceReconciliationPreviewService(
            ImportBatchRepository imports,
            InternalPaymentReadRepository payments,
            BankPreviewReadRepository evidence
    ) {
        this.imports = imports;
        this.payments = payments;
        this.evidence = evidence;
    }

    public ThreeSourceReconciliationPreview preview(
            UUID tenantId,
            UUID paymentImportId,
            UUID settlementImportId,
            UUID bankImportId
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(
                paymentImportId,
                "paymentImportId must not be null"
        );
        Objects.requireNonNull(
                settlementImportId,
                "settlementImportId must not be null"
        );
        Objects.requireNonNull(
                bankImportId,
                "bankImportId must not be null"
        );

        /*
         * Establish ownership of every selected import before
         * validating source types or reading any evidence.
         */
        ImportBatchEntity paymentImport = load(
                tenantId,
                paymentImportId
        );

        ImportBatchEntity settlementImport = load(
                tenantId,
                settlementImportId
        );

        ImportBatchEntity bankImport = load(
                tenantId,
                bankImportId
        );

        requireReady(paymentImport, SourceType.INTERNAL_PAYMENT);
        requireReady(settlementImport, SourceType.PROCESSOR_SETTLEMENT);
        requireReady(bankImport, SourceType.BANK_STATEMENT);

        long paymentCount = payments.count(tenantId, paymentImportId);

        if (paymentCount > MAX_PAYMENTS) {
            throw conflict(
                    "PREVIEW_LIMIT_EXCEEDED",
                    "Synchronous preview supports at most "
                            + MAX_PAYMENTS
                            + " payments per selected import."
            );
        }

        requireCount(paymentImport, paymentCount, "payments");

        /*
         * The additional row is a defensive limit check.
         * This repository method is also used by the paginated API;
         * the API's maximum page size is enforced in its own service.
         */
        List<PaymentEvidence.Item> paymentRows = payments.findPage(
                tenantId,
                paymentImportId,
                MAX_PAYMENTS + 1,
                0L
        );

        if (paymentRows.size() > MAX_PAYMENTS) {
            throw conflict(
                    "PREVIEW_LIMIT_EXCEEDED",
                    "The selected payment import exceeds the preview limit."
            );
        }

        if (paymentRows.size() != paymentCount) {
            throw conflict(
                    "EVIDENCE_INCONSISTENT",
                    "Loaded payment evidence does not match its count."
            );
        }

        List<PaymentRecord> paymentRecords = paymentRows.stream()
                .map(row -> new PaymentRecord(
                        row.paymentId(),
                        new MatchKey(
                                tenantId,
                                row.provider(),
                                row.paymentReference()
                        ),
                        Money.ofMinor(row.amountMinor(), row.currency())
                ))
                .toList();

        /*
         * These existing methods enforce their own 10,000-record
         * limits for batches, settlement lines, and bank credits.
         */
        List<SettlementBatch> batches = evidence.loadBatches(
                tenantId,
                settlementImportId
        );

        List<BankCredit> credits = evidence.loadCredits(
                tenantId,
                bankImportId
        );

        long settlementLineCount = batches.stream()
                .mapToLong(batch -> batch.lines().size())
                .sum();

        requireCount(
                settlementImport,
                settlementLineCount,
                "settlement lines"
        );

        requireCount(bankImport, credits.size(), "bank credits");

        CombinedReconciliationReport report = reconciliation.reconcile(
                paymentRecords,
                batches,
                credits
        );

        return new ThreeSourceReconciliationPreview(
                paymentImportId,
                settlementImportId,
                bankImportId,
                paymentRecords.size(),
                settlementLineCount,
                batches.size(),
                credits.size(),
                report.paymentReport().unresolvedPaymentCount(),
                report.unresolvedBatchCount(),
                report.paymentReport().orphanSettlementCount(),
                report.orphanBankCreditCount(),
                report.fullyReconciled(),
                report.paymentReport().outcomeCounts(),
                report.bankOutcomeCounts(),
                report.paymentReport().paymentResults().stream()
                        .map(this::paymentResult)
                        .toList(),
                report.paymentReport().orphanSettlementLines().stream()
                        .map(this::orphanLine)
                        .toList(),
                report.bankResults().stream()
                        .map(this::bankResult)
                        .toList(),
                report.orphanBankCredits().stream()
                        .map(this::orphanCredit)
                        .toList()
        );
    }

    private ImportBatchEntity load(UUID tenantId, UUID importId) {
        return imports.findByTenantIdAndId(tenantId, importId)
                .orElseThrow(
                        () -> new ImportNotFoundException(importId)
                );
    }

    private void requireReady(
            ImportBatchEntity imported,
            SourceType expectedSource
    ) {
        if (imported.getSourceType() != expectedSource) {
            throw conflict(
                    "INVALID_IMPORT_SOURCE",
                    "Selected import must have source type " + expectedSource
            );
        }

        if (imported.getStatus() != ImportStatus.READY) {
            throw conflict(
                    "IMPORT_NOT_READY",
                    "All three selected imports must be READY."
            );
        }
    }

    private void requireCount(
            ImportBatchEntity imported,
            long actualCount,
            String description
    ) {
        if (actualCount == 0) {
            throw conflict(
                    "EVIDENCE_MISSING",
                    "A selected READY import has no persisted " + description
            );
        }

        if (actualCount != imported.getRowCount()) {
            throw conflict(
                    "EVIDENCE_INCONSISTENT",
                    "Persisted " + description
                            + " do not match the import's recorded row count."
            );
        }
    }

    private ThreeSourceReconciliationPreview.PaymentResult paymentResult(
            MatchResult result
    ) {
        return new ThreeSourceReconciliationPreview.PaymentResult(
                result.paymentId(),
                result.matchKey().provider(),
                result.matchKey().paymentReference(),
                result.outcome(),
                result.candidateSettlementIds()
        );
    }

    private ThreeSourceReconciliationPreview.OrphanSettlementLine orphanLine(
            SettlementLine line
    ) {
        return new ThreeSourceReconciliationPreview.OrphanSettlementLine(
                line.id(),
                line.matchKey().provider(),
                line.matchKey().paymentReference(),
                line.grossAmount().amountMinor(),
                line.feeAmount().amountMinor(),
                line.netAmount().amountMinor(),
                line.grossAmount().currency().getCurrencyCode()
        );
    }

    private BankReconciliationPreview.BatchResult bankResult(
            BankMatchResult result
    ) {
        Money difference = result.difference().orElse(null);

        return new BankReconciliationPreview.BatchResult(
                result.settlementBatchId(),
                result.settlementKey().provider(),
                result.settlementKey().bankAccountReference(),
                result.settlementKey().settlementReference(),
                result.outcome(),
                result.candidateBankCreditIds(),
                difference == null ? null : difference.amountMinor(),
                difference == null
                        ? null
                        : difference.currency().getCurrencyCode()
        );
    }

    private BankReconciliationPreview.OrphanCredit orphanCredit(
            BankCredit credit
    ) {
        return new BankReconciliationPreview.OrphanCredit(
                credit.id(),
                credit.settlementKey().provider(),
                credit.settlementKey().bankAccountReference(),
                credit.settlementKey().settlementReference(),
                credit.amount().amountMinor(),
                credit.amount().currency().getCurrencyCode()
        );
    }

    private BankPreviewException conflict(String code, String message) {
        return new BankPreviewException(code, message);
    }
}