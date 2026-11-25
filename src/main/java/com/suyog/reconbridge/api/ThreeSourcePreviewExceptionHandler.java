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

@RestControllerAdvice(
        assignableTypes = ThreeSourceReconciliationPreviewController.class
)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ThreeSourcePreviewExceptionHandler {

    @ExceptionHandler(BankPreviewException.class)
    public ProblemDetail conflict(BankPreviewException exception) {
        return problem(
                HttpStatus.CONFLICT,
                exception.getCode(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail missingImport(ImportNotFoundException exception) {
        return problem(
                HttpStatus.NOT_FOUND,
                "IMPORT_NOT_FOUND",
                "Import not found."
        );
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ProblemDetail invalidParameters(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_PREVIEW_REQUEST",
                "Provide paymentImportId, settlementImportId, "
                        + "and bankImportId as valid UUIDs."
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

        problem.setProperty("code", code);

        return problem;
    }
}