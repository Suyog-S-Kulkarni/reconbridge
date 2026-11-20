package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ExceptionCaseOperationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        assignableTypes = ResolutionProposalQueryController.class
)
public class ResolutionProposalQueryExceptionHandler {

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

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleInvalidParameter(
            MethodArgumentTypeMismatchException exception
    ) {
        return problem(
                400,
                "INVALID_PROPOSAL_QUERY_PARAMETER",
                "Provide a valid case UUID and integer pagination parameters."
        );
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