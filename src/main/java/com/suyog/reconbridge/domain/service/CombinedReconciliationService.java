package com.suyog.reconbridge.domain.service;

import com.suyog.reconbridge.domain.matching.BankMatchingStrategy;
import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.BankMatchResult;
import com.suyog.reconbridge.domain.model.CombinedReconciliationReport;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.ReconciliationReport;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CombinedReconciliationService {

    private final PaymentReconciliationService paymentService;
    private final BankMatchingStrategy bankMatchingStrategy;

    public CombinedReconciliationService(
            PaymentReconciliationService paymentService,
            BankMatchingStrategy bankMatchingStrategy
    ) {
        this.paymentService = Objects.requireNonNull(
                paymentService,
                "paymentService must not be null"
        );

        this.bankMatchingStrategy = Objects.requireNonNull(
                bankMatchingStrategy,
                "bankMatchingStrategy must not be null"
        );
    }

    public CombinedReconciliationReport reconcile(
            List<PaymentRecord> payments,
            List<SettlementBatch> batches,
            List<BankCredit> bankCredits
    ) {
        Objects.requireNonNull(
                payments,
                "payments must not be null"
        );

        Objects.requireNonNull(
                batches,
                "batches must not be null"
        );

        Objects.requireNonNull(
                bankCredits,
                "bankCredits must not be null"
        );

        List<PaymentRecord> paymentSnapshot =
                List.copyOf(payments);

        List<SettlementBatch> batchSnapshot =
                List.copyOf(batches);

        List<BankCredit> creditSnapshot =
                List.copyOf(bankCredits);

        // Also validates batch identities, line ownership,
        // and duplicate bank credit IDs.
        List<BankMatchResult> bankResults =
                bankMatchingStrategy.match(
                        batchSnapshot,
                        creditSnapshot
                );

        // Both passes use the same settlement evidence.
        List<SettlementLine> settlementLines =
                batchSnapshot.stream()
                        .flatMap(batch -> batch.lines().stream())
                        .toList();

        ReconciliationReport paymentReport =
                paymentService.reconcile(
                        paymentSnapshot,
                        settlementLines
                );

        Set<SettlementKey> batchKeys = new HashSet<>();

        for (SettlementBatch batch : batchSnapshot) {
            batchKeys.add(batch.settlementKey());
        }

        List<BankCredit> orphanBankCredits =
                creditSnapshot.stream()
                        .filter(credit ->
                                !batchKeys.contains(
                                        credit.settlementKey()
                                )
                        )
                        .toList();

        return new CombinedReconciliationReport(
                paymentReport,
                bankResults,
                orphanBankCredits
        );
    }
}