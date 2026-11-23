package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.domain.model.PaymentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class InternalPaymentWriteRepository {

    private final JdbcTemplate jdbc;

    public InternalPaymentWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID tenantId,
            UUID importId,
            PaymentRecord payment
    ) {
        jdbc.update(
                """
                INSERT INTO internal_payment (
                    tenant_id,
                    import_id,
                    payment_id,
                    provider,
                    payment_reference,
                    amount_minor,
                    currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                importId.toString(),
                payment.id().toString(),
                payment.matchKey().provider(),
                payment.matchKey().paymentReference(),
                payment.amount().amountMinor(),
                payment.amount().currency().getCurrencyCode()
        );
    }
}