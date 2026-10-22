package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.domain.model.SettlementBatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class SettlementWriteRepository {

    private static final String INSERT_BATCH = """
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
            """;

    private static final String INSERT_LINE = """
            INSERT INTO settlement_line (
                tenant_id,
                import_id,
                line_id,
                batch_id,
                payment_reference,
                gross_minor,
                fee_minor,
                net_minor
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public SettlementWriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(
            UUID tenantId,
            UUID importId,
            List<SettlementBatch> batches
    ) {
        for (SettlementBatch batch : batches) {
            jdbcTemplate.update(
                    INSERT_BATCH,
                    tenantId.toString(),
                    importId.toString(),
                    batch.id().toString(),
                    batch.settlementKey().provider(),
                    batch.settlementKey().settlementReference(),
                    batch.settlementKey().bankAccountReference(),
                    batch.declaredNetAmount().amountMinor(),
                    batch.declaredNetAmount()
                            .currency()
                            .getCurrencyCode()
            );

            jdbcTemplate.batchUpdate(
                    INSERT_LINE,
                    batch.lines(),
                    500,
                    (statement, line) -> {
                        statement.setString(1, tenantId.toString());
                        statement.setString(2, importId.toString());
                        statement.setString(3, line.id().toString());
                        statement.setString(4, batch.id().toString());
                        statement.setString(
                                5,
                                line.matchKey().paymentReference()
                        );
                        statement.setLong(
                                6,
                                line.grossAmount().amountMinor()
                        );
                        statement.setLong(
                                7,
                                line.feeAmount().amountMinor()
                        );
                        statement.setLong(
                                8,
                                line.netAmount().amountMinor()
                        );
                    }
            );
        }
    }
}