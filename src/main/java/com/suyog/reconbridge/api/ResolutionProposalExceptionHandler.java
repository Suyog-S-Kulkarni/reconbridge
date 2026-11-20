package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        assignableTypes = ResolutionProposalController.class
)
public class ResolutionProposalExceptionHandler {

    @ExceptionHandler(ExceptionCaseOperationException.class)
    public ProblemDetail handleOperation(
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
    public ProblemDetail handleInvalidRequest(Exception exception) {
        return problem(
                400,
                "INVALID_PROPOSAL_REQUEST",
                "Provide valid UUIDs, a nonnegative expectedVersion, "
                        + "a supported resolutionType for submission, "
                        + "and nonblank text of at most 4000 characters."
        );
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleLockContention(
            PessimisticLockingFailureException exception
    ) {
        ProblemDetail problem = problem(
                503,
                "PROPOSAL_TEMPORARILY_BUSY",
                "The case is temporarily busy. Reload the case and proposal "
                        + "before retrying."
        );

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    private ProblemDetail problem(
            int status,
            String code,
            String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setDetail(detail);
        problem.setProperty("code", code);
        return problem;
    }
}