package com.suyog.reconbridge.application.model;

public sealed interface ReconciliationReportSnapshot
        permits BankReconciliationPreview,
        ThreeSourceReconciliationPreview {
}