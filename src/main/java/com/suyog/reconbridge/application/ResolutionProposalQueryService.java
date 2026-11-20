package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.Page;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.PendingApproval;
import com.suyog.reconbridge.application.model.ResolutionProposalReadModels.ProposalSummary;
import com.suyog.reconbridge.persistence.repository.ResolutionProposalQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
)
public class ResolutionProposalQueryService {

    private final ResolutionProposalQueryRepository repository;

    public ResolutionProposalQueryService(
            ResolutionProposalQueryRepository repository
    ) {
        this.repository = repository;
    }

    public Page<ProposalSummary> history(
            UUID tenantId,
            UUID caseId,
            int page,
            int size
    ) {
        requireTenant(tenantId);
        validatePagination(page, size);

        if (caseId == null) {
            throw problem(
                    400,
                    "INVALID_PROPOSAL_QUERY_PARAMETER",
                    "A valid case ID is required."
            );
        }

        if (!repository.caseExists(tenantId, caseId)) {
            throw problem(
                    404,
                    "CASE_NOT_FOUND",
                    "Exception case not found."
            );
        }

        long count = repository.countHistory(tenantId, caseId);

        List<ProposalSummary> items = repository.findHistory(
                tenantId,
                caseId,
                size,
                offset(page, size)
        );

        return Page.of(items, page, size, count);
    }

    public Page<PendingApproval> pending(
            UUID tenantId,
            String actorSubject,
            int page,
            int size
    ) {
        requireTenant(tenantId);
        validatePagination(page, size);

        if (actorSubject == null
                || actorSubject.isBlank()
                || actorSubject.length() > 255) {
            throw problem(
                    400,
                    "INVALID_CASE_ACTOR",
                    "A valid actor subject is required."
            );
        }

        long count = repository.countPending(
                tenantId,
                actorSubject
        );

        List<PendingApproval> items = repository.findPending(
                tenantId,
                actorSubject,
                size,
                offset(page, size)
        );

        return Page.of(items, page, size, count);
    }

    private void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw problem(
                    400,
                    "INVALID_PROPOSAL_QUERY_PARAMETER",
                    "A tenant ID is required."
            );
        }
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw problem(
                    400,
                    "INVALID_PAGINATION",
                    "page must be zero or greater and size must be between 1 and 100."
            );
        }
    }

    private long offset(int page, int size) {
        return (long) page * size;
    }

    private ExceptionCaseOperationException problem(
            int status,
            String code,
            String message
    ) {
        return new ExceptionCaseOperationException(
                status,
                code,
                message
        );
    }
}