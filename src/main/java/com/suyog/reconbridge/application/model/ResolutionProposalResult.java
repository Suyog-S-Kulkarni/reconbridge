package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.ResolutionProposal;

public record ResolutionProposalResult(
        StoredExceptionCase exceptionCase,
        ResolutionProposal proposal
) {
}