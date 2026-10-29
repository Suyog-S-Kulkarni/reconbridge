package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ReconciliationQueryException;
import com.suyog.reconbridge.application.exception.ReconciliationRunNotFoundException;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.Page;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.RunSummary;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.UnresolvedOutcome;
import com.suyog.reconbridge.application.model.ReconciliationReadModels.UnresolvedResult;
import com.suyog.reconbridge.persistence.repository.ReconciliationQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReconciliationQueryService {

    private final ReconciliationQueryRepository repository;

    public ReconciliationQueryService(
            ReconciliationQueryRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public Page<RunSummary> history(
            UUID tenantId,
            int page,
            int size
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        validatePage(page, size);

        long offset = (long) page * size;

        long total = repository.countRuns(tenantId);

        List<RunSummary> items = repository.findRuns(
                tenantId,
                size,
                offset
        );

        return Page.of(items, page, size, total);
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public Page<UnresolvedResult> unresolved(
            UUID tenantId,
            UUID runId,
            UnresolvedOutcome outcome,
            int page,
            int size
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        validatePage(page, size);

        /*
         * Distinguish an existing run with no unresolved results
         * from a missing or foreign-tenant run.
         */
        if (!repository.runExists(tenantId, runId)) {
            throw new ReconciliationRunNotFoundException(runId);
        }

        long offset = (long) page * size;

        long total = repository.countUnresolved(
                tenantId,
                runId,
                outcome
        );

        List<UnresolvedResult> items = repository.findUnresolved(
                tenantId,
                runId,
                outcome,
                size,
                offset
        );

        return Page.of(items, page, size, total);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ReconciliationQueryException(
                    "INVALID_PAGINATION",
                    "page must be zero or greater; "
                            + "size must be between 1 and 100."
            );
        }
    }
}