package com.suyog.reconbridge.api;

import com.suyog.reconbridge.domain.model.CaseEvidenceReference;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class ExceptionCaseRequests {

    private ExceptionCaseRequests() {
    }

    public record Open(
            @NotNull UUID runId,
            @NotNull CaseEvidenceReference.ItemType itemType,
            @NotNull UUID evidenceId
    ) {
    }

    public record Claim(
            @NotNull @Min(0) Long expectedVersion
    ) {
    }

    public record AddNote(
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 4000) String text
    ) {
    }
}