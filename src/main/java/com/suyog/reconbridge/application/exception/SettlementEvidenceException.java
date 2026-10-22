package com.suyog.reconbridge.application.exception;

public class SettlementEvidenceException extends RuntimeException {

    public enum Reason {
        INVALID_PAGINATION,
        IMPORT_NOT_READY,
        UNSUPPORTED_SOURCE,
        BATCH_NOT_FOUND
    }

    private final Reason reason;

    public SettlementEvidenceException(
            Reason reason,
            String message
    ) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}