package com.suyog.reconbridge.domain.matching;

import com.suyog.reconbridge.domain.model.MatchResult;
import com.suyog.reconbridge.domain.model.PaymentRecord;
import com.suyog.reconbridge.domain.model.SettlementLine;

import java.util.List;

public interface MatchingStrategy {

    List<MatchResult> match(
            List<PaymentRecord> payments,
            List<SettlementLine> settlementLines
    );
}