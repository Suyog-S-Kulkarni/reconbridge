package com.suyog.reconbridge.ingestion;

public final class PaymentCsvValidationException
        extends RuntimeException {

    public PaymentCsvValidationException(String message) {
        super(message);
    }

    public PaymentCsvValidationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}