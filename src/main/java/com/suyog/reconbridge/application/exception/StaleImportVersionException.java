package com.suyog.reconbridge.application.exception;

import java.util.UUID;

public class StaleImportVersionException extends RuntimeException {

    public StaleImportVersionException(
            UUID importId,
            long expectedVersion,
            long actualVersion
    ) {
        super(
                "Import " + importId
                        + " has changed: expected version "
                        + expectedVersion
                        + ", actual version "
                        + actualVersion
        );
    }
}