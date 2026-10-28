package com.suyog.reconbridge.application.exception;

public class RunIdempotencyException extends RuntimeException {

    private final int status;
    private final String code;

    public RunIdempotencyException(
            int status,
            String code,
            String message
    ) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}