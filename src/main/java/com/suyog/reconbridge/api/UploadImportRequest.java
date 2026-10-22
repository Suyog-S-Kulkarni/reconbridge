package com.suyog.reconbridge.api;

import com.suyog.reconbridge.domain.model.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record UploadImportRequest(

        @NotNull
        SourceType sourceType,

        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "[\\x20-\\x7E]+",
                message = "must contain printable ASCII characters"
        )
        String sourceSystem,

        @NotNull
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate businessDate
) {

    public UploadImportRequest {
        if (sourceSystem != null) {
            sourceSystem = sourceSystem.trim();
        }
    }
}