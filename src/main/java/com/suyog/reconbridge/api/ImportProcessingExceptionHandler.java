package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.ImportProcessingConflictException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ImportProcessingController.class)
public class ImportProcessingExceptionHandler {
    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail notFound(ImportNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Import not found.");
    }

    @ExceptionHandler(ImportProcessingConflictException.class)
    public ProblemDetail conflict(ImportProcessingConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler({StaleImportVersionException.class, OptimisticLockingFailureException.class})
    public ProblemDetail stale(Exception exception) {
        return problem(HttpStatus.CONFLICT, "STALE_IMPORT_VERSION",
                "The import changed. Retrieve its current state before taking further action.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ProblemDetail invalidBody(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PROCESS_REQUEST",
                "Provide a JSON body with a nonnegative integer expectedVersion.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail invalidId(MethodArgumentTypeMismatchException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_IMPORT_ID", "The import ID must be a valid UUID.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
