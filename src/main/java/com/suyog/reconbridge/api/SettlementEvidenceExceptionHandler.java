package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.SettlementEvidenceException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        assignableTypes = SettlementEvidenceController.class
)
public class SettlementEvidenceExceptionHandler {

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail missingImport(
            ImportNotFoundException exception
    ) {
        return problem(
                404,
                "IMPORT_NOT_FOUND",
                "Import not found."
        );
    }

    @ExceptionHandler(SettlementEvidenceException.class)
    public ProblemDetail evidence(
            SettlementEvidenceException exception
    ) {
        int status = switch (exception.getReason()) {
            case INVALID_PAGINATION -> 400;
            case BATCH_NOT_FOUND -> 404;
            case IMPORT_NOT_READY, UNSUPPORTED_SOURCE -> 409;
        };

        return problem(
                status,
                exception.getReason().name(),
                exception.getMessage()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail malformedArgument(
            MethodArgumentTypeMismatchException exception
    ) {
        return problem(
                400,
                "INVALID_REQUEST_PARAMETER",
                "Import and batch IDs must be UUIDs; page and size must be integers."
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