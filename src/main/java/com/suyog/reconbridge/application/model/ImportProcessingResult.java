package com.suyog.reconbridge.application.model;

public record ImportProcessingResult(
        ImportBatchSnapshot imported,
        String code,
        String detail
) {
}
