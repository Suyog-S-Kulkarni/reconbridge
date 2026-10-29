package com.suyog.reconbridge.application.exception;

public class ReconciliationQueryException extends RuntimeException {

    private final String code;

    public ReconciliationQueryException(
            String code,
            String message
    ) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}