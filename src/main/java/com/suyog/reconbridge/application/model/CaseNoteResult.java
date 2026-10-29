package com.suyog.reconbridge.application.model;

import com.suyog.reconbridge.domain.model.InvestigationNote;

public record CaseNoteResult(
        StoredExceptionCase exceptionCase,
        InvestigationNote note
) {
}