package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.DuplicateImportException;
import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.TenantNotProvisionedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice(assignableTypes = ImportController.class)
public class ImportApiExceptionHandler {

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail handleImportNotFound(
            ImportNotFoundException exception
    ) {
        return problem(
                HttpStatus.NOT_FOUND,
                "Import not found",
                "Import not found.",
                "IMPORT_NOT_FOUND"
        );
    }

    @ExceptionHandler(DuplicateImportException.class)
    public ProblemDetail handleDuplicateImport(
            DuplicateImportException exception
    ) {
        return problem(
                HttpStatus.CONFLICT,
                "Duplicate import",
                exception.getMessage(),
                "DUPLICATE_IMPORT"
        );
    }

    @ExceptionHandler(TenantNotProvisionedException.class)
    public ProblemDetail handleTenantNotProvisioned(
            TenantNotProvisionedException exception
    ) {
        return problem(
                HttpStatus.FORBIDDEN,
                "Tenant not provisioned",
                exception.getMessage(),
                "TENANT_NOT_PROVISIONED"
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleInvalidArgument(
            MethodArgumentTypeMismatchException exception
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid import ID",
                "The import ID must be a valid UUID.",
                "INVALID_IMPORT_ID"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException exception
    ) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "Invalid import request",
                "One or more request fields are invalid.",
                "VALIDATION_FAILED"
        );

        List<FieldViolation> errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldViolation(
                        error.getField(),
                        error.getDefaultMessage()
                ))
                .toList();

        problem.setProperty("errors", errors);

        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(
            HttpMessageNotReadableException exception
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid request body",
                "Provide valid JSON with supported source types "
                        + "and dates in YYYY-MM-DD format.",
                "INVALID_REQUEST_BODY"
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String code
    ) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(status, detail);

        problem.setTitle(title);
        problem.setProperty("code", code);

        return problem;
    }

    public record FieldViolation(String field, String message) {
    }
}