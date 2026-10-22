package com.suyog.reconbridge.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ProcessImportRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
