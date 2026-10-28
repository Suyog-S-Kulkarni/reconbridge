package com.suyog.reconbridge.application.exception;

public class BankPreviewException extends RuntimeException {

    private final String code;

    public BankPreviewException(
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