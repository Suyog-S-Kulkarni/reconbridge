package com.suyog.reconbridge.application.exception;

public class ImportProcessingConflictException extends RuntimeException {
    private final String code;

    public ImportProcessingConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
