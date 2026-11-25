package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.application.model.PaymentEvidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class InternalPaymentReadRepository {

    private final JdbcTemplate jdbc;

    public InternalPaymentReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(
            UUID tenantId,
            UUID importId
    ) {
        Long result = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM internal_payment
                WHERE tenant_id = ?
                  AND import_id = ?
                """,
                Long.class,
                tenantId.toString(),
                importId.toString()
        );

        return result == null ? 0L : result;
    }

    public List<PaymentEvidence.Item> findPage(
            UUID tenantId,
            UUID importId,
            int limit,
            long offset
    ) {
        return jdbc.query(
                """
                SELECT
                    payment_id,
                    provider,
                    payment_reference,
                    amount_minor,
                    currency
                FROM internal_payment
                WHERE tenant_id = ?
                  AND import_id = ?
                ORDER BY payment_id ASC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNumber) -> new PaymentEvidence.Item(
                        UUID.fromString(rs.getString("payment_id")),
                        rs.getString("provider"),
                        rs.getString("payment_reference"),
                        rs.getLong("amount_minor"),
                        rs.getString("currency")
                ),
                tenantId.toString(),
                importId.toString(),
                limit,
                offset
        );
    }
}