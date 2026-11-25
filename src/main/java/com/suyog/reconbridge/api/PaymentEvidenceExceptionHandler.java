package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.PaymentEvidenceException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(
        assignableTypes = PaymentEvidenceController.class
)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentEvidenceExceptionHandler {

    @ExceptionHandler(PaymentEvidenceException.class)
    public ProblemDetail paymentEvidence(
            PaymentEvidenceException exception
    ) {
        return problem(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail importNotFound(
            ImportNotFoundException exception
    ) {
        return problem(
                404,
                "IMPORT_NOT_FOUND",
                "Import not found."
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail invalidParameter(
            MethodArgumentTypeMismatchException exception
    ) {
        return problem(
                400,
                "INVALID_PAYMENT_QUERY_PARAMETER",
                "importId must be a valid UUID; "
                        + "page and size must be integers."
        );
    }

    private ProblemDetail problem(
            int status,
            String code,
            String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(status),
                detail
        );

        problem.setProperty("code", code);

        return problem;
    }
}