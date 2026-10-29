package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.CaseSummary;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.NoteSummary;
import com.suyog.reconbridge.application.model.ExceptionCaseReadModels.Page;
import com.suyog.reconbridge.domain.model.ExceptionCaseStatus;
import com.suyog.reconbridge.persistence.repository.ExceptionCaseQueryRepository;
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
public class ExceptionCaseQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExceptionCaseQueryRepository repository;

    public ExceptionCaseQueryService(
            ExceptionCaseQueryRepository repository
    ) {
        this.repository = repository;
    }

    public Page<CaseSummary> listCases(
            UUID tenantId,
            String actorSubject,
            ExceptionCaseStatus status,
            boolean assignedToMe,
            int page,
            int size
    ) {
        validatePagination(page, size);

        String assignedTo = null;

        if (assignedToMe) {
            if (actorSubject == null || actorSubject.isBlank()) {
                throw new ExceptionCaseOperationException(
                        400,
                        "INVALID_CASE_ACTOR",
                        "A subject is required for the assignment filter."
                );
            }

            assignedTo = actorSubject;
        }

        long totalElements = repository.countCases(
                tenantId,
                status,
                assignedTo
        );

        List<CaseSummary> items = repository.findCases(
                tenantId,
                status,
                assignedTo,
                size,
                offset(page, size)
        );

        return Page.of(items, page, size, totalElements);
    }

    public Page<NoteSummary> listNotes(
            UUID tenantId,
            UUID caseId,
            int page,
            int size
    ) {
        validatePagination(page, size);

        if (!repository.caseExists(tenantId, caseId)) {
            throw new ExceptionCaseOperationException(
                    404,
                    "CASE_NOT_FOUND",
                    "Exception case not found."
            );
        }

        long totalElements = repository.countNotes(
                tenantId,
                caseId
        );

        List<NoteSummary> items = repository.findNotes(
                tenantId,
                caseId,
                size,
                offset(page, size)
        );

        return Page.of(items, page, size, totalElements);
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ExceptionCaseOperationException(
                    400,
                    "INVALID_PAGINATION",
                    "page must be zero or greater and size must be between 1 and 100."
            );
        }
    }

    private long offset(int page, int size) {
        return (long) page * size;
    }
}