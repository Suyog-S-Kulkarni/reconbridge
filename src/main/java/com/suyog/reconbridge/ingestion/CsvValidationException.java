package com.suyog.reconbridge.ingestion;

public class CsvValidationException extends RuntimeException {

    private final long recordNumber;

    public CsvValidationException(
            long recordNumber,
            String message
    ) {
        super(prefix(recordNumber) + message);
        this.recordNumber = recordNumber;
    }

    public CsvValidationException(
            long recordNumber,
            String message,
            Throwable cause
    ) {
        super(prefix(recordNumber) + message, cause);
        this.recordNumber = recordNumber;
    }

    public long getRecordNumber() {
        return recordNumber;
    }

    private static String prefix(long recordNumber) {
        return recordNumber > 0
                ? "CSV record " + recordNumber + ": "
                : "CSV: ";
    }
}