package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
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
@RestControllerAdvice(
        assignableTypes = ExceptionCaseController.class
)
public class ExceptionCaseExceptionHandler {

    @ExceptionHandler(ExceptionCaseOperationException.class)
    public ProblemDetail operationFailed(
            ExceptionCaseOperationException exception
    ) {
        return problem(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage()
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
                "INVALID_CASE_REQUEST",
                "Provide valid identifiers and required fields. "
                        + "expectedVersion must be zero or greater, "
                        + "and note text must contain 1 to 4000 characters."
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
                                "CASE_TEMPORARILY_BUSY",
                                "The database could not complete the operation "
                                        + "because of lock contention. "
                                        + "Reload the case before retrying."
                        )
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