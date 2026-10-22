package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportProcessingConflictException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.application.model.ImportProcessingResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ImportProcessingService {

    private final ImportLifecycleService lifecycle;
    private final SettlementImportProcessingService settlementProcessing;
    private final BankImportProcessingService bankProcessing;

    public ImportProcessingService(
            ImportLifecycleService lifecycle,
            SettlementImportProcessingService settlementProcessing,
            BankImportProcessingService bankProcessing
    ) {
        this.lifecycle = lifecycle;
        this.settlementProcessing = settlementProcessing;
        this.bankProcessing = bankProcessing;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportProcessingResult process(
            UUID tenantId,
            UUID importId,
            long expectedVersion
    ) {
        ImportBatchSnapshot imported = lifecycle.get(
                tenantId,
                importId
        );

        return switch (imported.sourceType()) {
            case PROCESSOR_SETTLEMENT ->
                    settlementProcessing.process(
                            tenantId,
                            importId,
                            expectedVersion
                    );

            case BANK_STATEMENT ->
                    bankProcessing.process(
                            tenantId,
                            importId,
                            expectedVersion
                    );

            case INTERNAL_PAYMENT ->
                    throw new ImportProcessingConflictException(
                            "UNSUPPORTED_SOURCE",
                            "Internal payment processing is not implemented yet."
                    );
        };
    }
}