package com.suyog.reconbridge.application.exception;

public class DuplicateImportException extends RuntimeException {

    public DuplicateImportException(Throwable cause) {
        super(
                "An import with this content already exists "
                        + "for the same tenant, source type and source system.",
                cause
        );
    }
}