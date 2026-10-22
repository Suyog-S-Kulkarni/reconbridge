package com.suyog.reconbridge.application;

import com.suyog.reconbridge.application.exception.ImportNotFoundException;
import com.suyog.reconbridge.application.exception.StaleImportVersionException;
import com.suyog.reconbridge.application.model.ImportBatchSnapshot;
import com.suyog.reconbridge.domain.model.ImportStatus;
import com.suyog.reconbridge.domain.model.MatchKey;
import com.suyog.reconbridge.domain.model.Money;
import com.suyog.reconbridge.domain.model.SettlementBatch;
import com.suyog.reconbridge.domain.model.SettlementKey;
import com.suyog.reconbridge.domain.model.SettlementLine;
import com.suyog.reconbridge.domain.model.SourceType;
import com.suyog.reconbridge.persistence.entity.ImportBatchEntity;
import com.suyog.reconbridge.persistence.entity.TenantEntity;
import com.suyog.reconbridge.persistence.repository.ImportBatchRepository;
import com.suyog.reconbridge.persistence.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SettlementPersistenceServiceIntegrationTest {

    private static final String PROVIDER = "PROCESSOR_A";

    @Autowired
    private SettlementPersistenceService persistenceService;

    @Autowired
    private ImportLifecycleService lifecycleService;

    @Autowired
    private ImportBatchRepository importRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID importId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();

        tenantRepository.saveAndFlush(
                new TenantEntity(tenantId, "Settlement Persistence Tenant")
        );

        importId = createImport(
                SourceType.PROCESSOR_SETTLEMENT,
                "a".repeat(64)
        );
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM settlement_line WHERE tenant_id = ?",
                tenantId.toString()
        );

        jdbcTemplate.update(
                "DELETE FROM settlement_batch WHERE tenant_id = ?",
                tenantId.toString()
        );

        jdbcTemplate.update(
                "DELETE FROM import_batch WHERE tenant_id = ?",
                tenantId.toString()
        );

        jdbcTemplate.update(
                "DELETE FROM tenant WHERE id = ?",
                tenantId.toString()
        );
    }

    @Test
    void savesBatchesAndLinesAndMarksImportReady() {
        ImportBatchSnapshot processing = startProcessing(importId);

        SettlementBatch first = batch(tenantId, "SETTLEMENT-001");
        SettlementBatch second = batch(tenantId, "SETTLEMENT-002");

        ImportBatchSnapshot completed = persistenceService.complete(
                tenantId,
                importId,
                processing.version(),
                List.of(first, second)
        );

        assertThat(completed.status()).isEqualTo(ImportStatus.READY);
        assertThat(completed.rowCount()).isEqualTo(2);
        assertThat(completed.version())
                .isEqualTo(processing.version() + 1);

        ImportBatchSnapshot stored =
                lifecycleService.get(tenantId, importId);

        assertThat(stored.status()).isEqualTo(ImportStatus.READY);
        assertThat(stored.rowCount()).isEqualTo(2);

        assertThat(batchCount()).isEqualTo(2);
        assertThat(lineCount()).isEqualTo(2);

        Long totalNet = jdbcTemplate.queryForObject(
                """
                SELECT SUM(net_minor)
                FROM settlement_line
                WHERE tenant_id = ? AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        assertThat(totalNet).isEqualTo(24800L);
    }

    @Test
    void rejectsStaleVersionWithoutWritingRecords() {
        ImportBatchSnapshot processing = startProcessing(importId);

        assertThatThrownBy(() -> persistenceService.complete(
                tenantId,
                importId,
                processing.version() - 1,
                List.of(batch(tenantId, "SETTLEMENT-001"))
        )).isInstanceOf(StaleImportVersionException.class);

        assertUnchangedProcessing(processing);
        assertThat(batchCount()).isZero();
        assertThat(lineCount()).isZero();
    }

    @Test
    void anotherTenantCannotCompleteTheImport() {
        ImportBatchSnapshot processing = startProcessing(importId);

        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> persistenceService.complete(
                otherTenant,
                importId,
                processing.version(),
                List.of(batch(otherTenant, "SETTLEMENT-001"))
        )).isInstanceOf(ImportNotFoundException.class);

        assertUnchangedProcessing(processing);
        assertThat(batchCount()).isZero();
        assertThat(lineCount()).isZero();
    }

    @Test
    void rejectsBatchContainingAnotherTenantIdentity() {
        ImportBatchSnapshot processing = startProcessing(importId);

        SettlementBatch foreignBatch =
                batch(UUID.randomUUID(), "SETTLEMENT-001");

        assertThatThrownBy(() -> persistenceService.complete(
                tenantId,
                importId,
                processing.version(),
                List.of(foreignBatch)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different tenant");

        assertUnchangedProcessing(processing);
        assertThat(batchCount()).isZero();
        assertThat(lineCount()).isZero();
    }

    @Test
    void failedSecondInsertRollsBackFirstInsertAndReadyTransition() {
        ImportBatchSnapshot processing = startProcessing(importId);

        SettlementBatch first = batch(tenantId, "SETTLEMENT-001");
        SettlementBatch conflicting = batch(tenantId, "SETTLEMENT-002");

        /*
         * Deliberately seed a conflicting batch to force a real database
         * failure after the service has inserted the first batch and line.
         */
        jdbcTemplate.update(
                """
                INSERT INTO settlement_batch (
                    tenant_id,
                    import_id,
                    batch_id,
                    provider,
                    settlement_reference,
                    bank_account_reference,
                    declared_net_minor,
                    currency
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                importId.toString(),
                conflicting.id().toString(),
                PROVIDER,
                conflicting.settlementKey().settlementReference(),
                conflicting.settlementKey().bankAccountReference(),
                conflicting.declaredNetAmount().amountMinor(),
                "INR"
        );

        assertThatThrownBy(() -> persistenceService.complete(
                tenantId,
                importId,
                processing.version(),
                List.of(first, conflicting)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertUnchangedProcessing(processing);

        // Only the deliberately pre-existing conflicting batch remains.
        assertThat(batchCount()).isEqualTo(1);
        assertThat(lineCount()).isZero();

        Long firstBatchCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_batch
                WHERE tenant_id = ? AND import_id = ? AND batch_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString(),
                first.id().toString()
        );

        assertThat(firstBatchCount).isZero();
    }

    @Test
    void receivedImportCannotBeCompletedWithoutProcessingClaim() {
        ImportBatchSnapshot received =
                lifecycleService.get(tenantId, importId);

        assertThatThrownBy(() -> persistenceService.complete(
                tenantId,
                importId,
                received.version(),
                List.of(batch(tenantId, "SETTLEMENT-001"))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROCESSING");

        assertThat(lifecycleService.get(tenantId, importId).status())
                .isEqualTo(ImportStatus.RECEIVED);

        assertThat(batchCount()).isZero();
        assertThat(lineCount()).isZero();
    }

    @Test
    void rejectsWrongImportSourceType() {
        UUID bankImportId = createImport(
                SourceType.BANK_STATEMENT,
                "b".repeat(64)
        );

        ImportBatchSnapshot processing = startProcessing(bankImportId);

        assertThatThrownBy(() -> persistenceService.complete(
                tenantId,
                bankImportId,
                processing.version(),
                List.of(batch(tenantId, "SETTLEMENT-001"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PROCESSOR_SETTLEMENT");

        ImportBatchSnapshot after =
                lifecycleService.get(tenantId, bankImportId);

        assertThat(after.status()).isEqualTo(ImportStatus.PROCESSING);
        assertThat(after.version()).isEqualTo(processing.version());
        assertThat(after.rowCount()).isZero();

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_batch WHERE tenant_id = ?",
                Long.class,
                tenantId.toString()
        );

        assertThat(rows).isZero();
    }

    private UUID createImport(SourceType sourceType, String hash) {
        ImportBatchEntity imported = new ImportBatchEntity(
                tenantId,
                sourceType,
                PROVIDER,
                "settlement.csv",
                "test://settlement.csv",
                hash,
                LocalDate.of(2026, 10, 21)
        );

        return importRepository.saveAndFlush(imported).getId();
    }

    private ImportBatchSnapshot startProcessing(UUID id) {
        ImportBatchSnapshot received =
                lifecycleService.get(tenantId, id);

        return lifecycleService.startProcessing(
                tenantId,
                id,
                received.version()
        );
    }

    private SettlementBatch batch(
            UUID owner,
            String settlementReference
    ) {
        SettlementLine line = new SettlementLine(
                UUID.randomUUID(),
                new MatchKey(
                        owner,
                        PROVIDER,
                        "PAY-" + UUID.randomUUID()
                ),
                Money.ofMinor(12550, "INR"),
                Money.ofMinor(150, "INR"),
                Money.ofMinor(12400, "INR")
        );

        return new SettlementBatch(
                UUID.randomUUID(),
                new SettlementKey(
                        owner,
                        PROVIDER,
                        "BANK-ACCOUNT-001",
                        settlementReference
                ),
                List.of(line),
                Money.ofMinor(12400, "INR")
        );
    }

    private void assertUnchangedProcessing(
            ImportBatchSnapshot before
    ) {
        ImportBatchSnapshot after =
                lifecycleService.get(tenantId, importId);

        assertThat(after.status()).isEqualTo(ImportStatus.PROCESSING);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.rowCount()).isEqualTo(before.rowCount());
    }

    private long batchCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_batch
                WHERE tenant_id = ? AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        return count == null ? 0 : count;
    }

    private long lineCount() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM settlement_line
                WHERE tenant_id = ? AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        return count == null ? 0 : count;
    }
}