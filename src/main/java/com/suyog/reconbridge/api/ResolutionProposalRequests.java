package com.suyog.reconbridge.api;

import com.suyog.reconbridge.domain.model.ResolutionType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ResolutionProposalRequests {

    private ResolutionProposalRequests() {
    }

    public record Submit(
            @NotNull
            @Min(0)
            Long expectedVersion,

            @NotNull
            ResolutionType resolutionType,

            @NotBlank
            @Size(max = 4000)
            String rationale
    ) {
    }

    public record Decision(
            @NotNull
            @Min(0)
            Long expectedVersion,

            @NotBlank
            @Size(max = 4000)
            String comment
    ) {
    }
}