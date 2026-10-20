package com.suyog.reconbridge.domain.matching;

import com.suyog.reconbridge.domain.model.BankCredit;
import com.suyog.reconbridge.domain.model.BankMatchResult;
import com.suyog.reconbridge.domain.model.SettlementBatch;

import java.util.List;

public interface BankMatchingStrategy {

    List<BankMatchResult> match(
            List<SettlementBatch> batches,
            List<BankCredit> bankCredits
    );
}