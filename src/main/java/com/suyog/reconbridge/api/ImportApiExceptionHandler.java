package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ImportController.class)
public class ImportApiExceptionHandler {

    @ExceptionHandler(ImportNotFoundException.class)
    public ProblemDetail handleImportNotFound(
            ImportNotFoundException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "Import not found."
        );

        problem.setTitle("Import not found");
        problem.setProperty("code", "IMPORT_NOT_FOUND");

        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleInvalidArgument(
            MethodArgumentTypeMismatchException exception
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The import ID must be a valid UUID."
        );

        problem.setTitle("Invalid import ID");
        problem.setProperty("code", "INVALID_IMPORT_ID");

        return problem;
    }
}