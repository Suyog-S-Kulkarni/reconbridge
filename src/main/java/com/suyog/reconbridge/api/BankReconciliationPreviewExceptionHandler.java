package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.BankPreviewException;
import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        assignableTypes = BankReconciliationPreviewController.class
)
public class BankReconciliationPreviewExceptionHandler {

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail handleImportNotFound(
            ImportNotFoundException exception
    ) {
        return problem(
                HttpStatus.NOT_FOUND,
                "IMPORT_NOT_FOUND",
                "A selected import was not found."
        );
    }

    @ExceptionHandler(BankPreviewException.class)
    public ProblemDetail handlePreviewConflict(
            BankPreviewException exception
    ) {
        return problem(
                HttpStatus.CONFLICT,
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ProblemDetail handleInvalidParameters(
            Exception exception
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_PREVIEW_PARAMETERS",
                "Provide settlementImportId and bankImportId as valid UUIDs."
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            String code,
            String detail
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                detail
        );

        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);

        return problem;
    }
}