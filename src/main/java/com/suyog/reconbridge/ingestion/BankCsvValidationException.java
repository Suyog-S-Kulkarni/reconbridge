package com.suyog.reconbridge.ingestion;

public class BankCsvValidationException extends RuntimeException {

    public BankCsvValidationException(String message) {
        super(message);
    }

    public BankCsvValidationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}