package com.suyog.reconbridge.api;

import com.suyog.reconbridge.application.exception.FileStorageException;
import com.suyog.reconbridge.application.exception.InvalidUploadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class UploadApiExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(UploadApiExceptionHandler.class);

    @ExceptionHandler(InvalidUploadException.class)
    public ProblemDetail invalidUpload(InvalidUploadException exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Invalid upload",
                exception.getMessage(),
                "INVALID_UPLOAD"
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge(
            MaxUploadSizeExceededException exception
    ) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Upload too large",
                "The upload exceeds the configured size limit.",
                "UPLOAD_TOO_LARGE"
        );
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ProblemDetail missingPart(
            MissingServletRequestPartException exception
    ) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "Missing upload part",
                "The request must contain a file part named 'file'.",
                "MISSING_FILE"
        );
    }

    @ExceptionHandler(FileStorageException.class)
    public ProblemDetail storageFailure(FileStorageException exception) {
        log.error("File storage operation failed", exception);

        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Storage failure",
                "The uploaded file could not be stored.",
                "STORAGE_FAILURE"
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            String title,
            String detail,
            String code
    ) {
        ProblemDetail result =
                ProblemDetail.forStatusAndDetail(status, detail);

        result.setTitle(title);
        result.setProperty("code", code);

        return result;
    }
}