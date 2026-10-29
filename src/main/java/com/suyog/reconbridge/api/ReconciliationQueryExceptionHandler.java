package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ReconciliationQueryException;
import com.suyog.reconbridge.application.exception.ReconciliationRunNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        assignableTypes = ReconciliationQueryController.class
)
public class ReconciliationQueryExceptionHandler {

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

    @ExceptionHandler(ReconciliationQueryException.class)
    public ProblemDetail invalidPagination(
            ReconciliationQueryException exception
    ) {
        return problem(
                400,
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail invalidParameter(
            MethodArgumentTypeMismatchException exception
    ) {
        return problem(
                400,
                "INVALID_QUERY_PARAMETER",
                "Provide a valid run UUID, integer page and size, "
                        + "and a supported unresolved outcome."
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