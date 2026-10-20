package com.suyog.reconbridge.domain.matching;

import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.BankMatchResult;
import com.suyog.reconbridge.domain.model.MatchOutcome;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ExactSettlementBankMatchingStrategy
        implements BankMatchingStrategy {

    @Override
    public List<BankMatchResult> match(
            List<SettlementBatch> batches,
            List<BankCredit> bankCredits
    ) {
        Objects.requireNonNull(
                batches,
                "batches must not be null"
        );

        Objects.requireNonNull(
                bankCredits,
                "bankCredits must not be null"
        );

        List<SettlementBatch> batchSnapshot =
                List.copyOf(batches);

        List<BankCredit> creditSnapshot =
                List.copyOf(bankCredits);

        validateBatches(batchSnapshot);

        Map<SettlementKey, List<BankCredit>> index =
                buildCreditIndex(creditSnapshot);

        List<BankMatchResult> results =
                new ArrayList<>(batchSnapshot.size());

        for (SettlementBatch batch : batchSnapshot) {
            List<BankCredit> candidates = index.getOrDefault(
                    batch.settlementKey(),
                    List.of()
            );

            results.add(evaluate(batch, candidates));
        }

        return List.copyOf(results);
    }

    private void validateBatches(
            List<SettlementBatch> batches
    ) {
        Set<UUID> batchIds = new HashSet<>();
        Set<SettlementKey> batchKeys = new HashSet<>();
        Set<UUID> allocatedLineIds = new HashSet<>();

        for (SettlementBatch batch : batches) {
            if (!batchIds.add(batch.id())) {
                throw new IllegalArgumentException(
                        "Duplicate settlement batch ID: " + batch.id()
                );
            }

            if (!batchKeys.add(batch.settlementKey())) {
                throw new IllegalArgumentException(
                        "Duplicate settlement batch key: "
                                + batch.settlementKey()
                );
            }

            for (SettlementLine line : batch.lines()) {
                if (!allocatedLineIds.add(line.id())) {
                    throw new IllegalArgumentException(
                            "Settlement line belongs to multiple batches: "
                                    + line.id()
                    );
                }
            }
        }
    }

    private Map<SettlementKey, List<BankCredit>>
    buildCreditIndex(List<BankCredit> credits) {

        Map<SettlementKey, List<BankCredit>> index =
                new HashMap<>();

        Set<UUID> creditIds = new HashSet<>();

        for (BankCredit credit : credits) {
            if (!creditIds.add(credit.id())) {
                throw new IllegalArgumentException(
                        "Duplicate bank credit ID: " + credit.id()
                );
            }

            index.computeIfAbsent(
                    credit.settlementKey(),
                    ignored -> new ArrayList<>()
            ).add(credit);
        }

        return index;
    }

    private BankMatchResult evaluate(
            SettlementBatch batch,
            List<BankCredit> candidates
    ) {
        List<UUID> candidateIds = candidates.stream()
                .map(BankCredit::id)
                .toList();

        if (candidates.isEmpty()) {
            return new BankMatchResult(
                    batch.id(),
                    batch.settlementKey(),
                    MatchOutcome.MISSING_COUNTERPART,
                    candidateIds,
                    Optional.empty()
            );
        }

        if (candidates.size() > 1) {
            return new BankMatchResult(
                    batch.id(),
                    batch.settlementKey(),
                    MatchOutcome.AMBIGUOUS,
                    candidateIds,
                    Optional.empty()
            );
        }

        BankCredit credit = candidates.get(0);

        if (!batch.declaredNetAmount().currency()
                .equals(credit.amount().currency())) {
            return new BankMatchResult(
                    batch.id(),
                    batch.settlementKey(),
                    MatchOutcome.CURRENCY_MISMATCH,
                    candidateIds,
                    Optional.empty()
            );
        }

        Money difference = batch.declaredNetAmount()
                .subtract(credit.amount());

        MatchOutcome outcome = difference.isZero()
                ? MatchOutcome.MATCHED
                : MatchOutcome.AMOUNT_MISMATCH;

        return new BankMatchResult(
                batch.id(),
                batch.settlementKey(),
                outcome,
                candidateIds,
                Optional.of(difference)
        );
    }
}