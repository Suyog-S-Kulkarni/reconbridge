package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.BankPreviewException;
import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.ReconciliationRunNotFoundException;
import com.suyog.reconbridge.application.exception.RunIdempotencyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReconciliationRunController.class)
public class ReconciliationRunExceptionHandler {

    @ExceptionHandler(ReconciliationRunNotFoundException.class)
    public ProblemDetail missingRun(
            ReconciliationRunNotFoundException exception
    ) {
        return problem(
                404,
                "RUN_NOT_FOUND",
                "Reconciliation run not found."
        );
    }

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail missingImport(
            ImportNotFoundException exception
    ) {
        return problem(
                404,
                "IMPORT_NOT_FOUND",
                "A selected import was not found."
        );
    }

    @ExceptionHandler(BankPreviewException.class)
    public ProblemDetail invalidEvidence(
            BankPreviewException exception
    ) {
        return problem(
                409,
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(RunIdempotencyException.class)
    public ProblemDetail idempotencyProblem(
            RunIdempotencyException exception
    ) {
        return problem(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> temporarilyBusy(
            PessimisticLockingFailureException exception
    ) {
        return ResponseEntity
                .status(503)
                .header("Retry-After", "1")
                .body(
                        problem(
                                503,
                                "RUN_REQUEST_BUSY",
                                "The request could not acquire its "
                                        + "database lock. Retry with the "
                                        + "same Idempotency-Key and body."
                        )
                );
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ProblemDetail invalidRequest(Exception exception) {
        return problem(
                400,
                "INVALID_RUN_REQUEST",
                "Provide valid UUID identifiers and all required request fields."
        );
    }

    private ProblemDetail problem(
            int status,
            String code,
            String detail
    ) {
        ProblemDetail result = ProblemDetail.forStatus(status);
        result.setDetail(detail);
        result.setProperty("code", code);
        return result;
    }
}