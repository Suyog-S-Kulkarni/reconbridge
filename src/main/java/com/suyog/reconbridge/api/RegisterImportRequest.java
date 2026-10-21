package com.suyog.reconbridge.api;

import com.suyog.reconbridge.domain.model.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterImportRequest(

        @NotNull
        SourceType sourceType,

        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "[\\x20-\\x7E]+",
                message = "must contain printable ASCII characters"
        )
        String sourceSystem,

        @NotBlank
        @Size(max = 255)
        String fileName,

        @NotBlank
        @Size(max = 1024)
        String fileUri,

        @NotBlank
        @Pattern(
                regexp = "[0-9a-f]{64}",
                message = "must contain exactly 64 lowercase hexadecimal characters"
        )
        String fileHash,

        @NotNull
        LocalDate businessDate
) {

    public RegisterImportRequest {
        sourceSystem = trim(sourceSystem);
        fileName = trim(fileName);
        fileUri = trim(fileUri);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}