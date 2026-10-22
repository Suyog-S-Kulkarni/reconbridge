package com.suyog.reconbridge.storage;

import java.io.IOException;

public class StoredFileReadException extends IOException {

    public enum Reason {
        INVALID_REFERENCE,
        INVALID_HASH,
        FILE_UNAVAILABLE,
        EMPTY_FILE,
        SIZE_LIMIT_EXCEEDED,
        HASH_MISMATCH
    }

    private final Reason reason;

    public StoredFileReadException(
            Reason reason,
            String message
    ) {
        super(message);
        this.reason = reason;
    }

    public StoredFileReadException(
            Reason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}