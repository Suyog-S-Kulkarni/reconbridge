package com.suyog.reconbridge.persistence.repository;

import com.suyog.reconbridge.domain.model.BankCredit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class BankCreditWriteRepository {

    private final JdbcTemplate jdbc;

    public BankCreditWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID tenantId,
            UUID importId,
            BankCredit credit
    ) {
        jdbc.update(
                """
                INSERT INTO bank_credit (
                    tenant_id,
                    import_id,
                    credit_id,
                    provider,
                    bank_account_reference,
                    settlement_reference,
                    amount_minor,
                    currency
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId.toString(),
                importId.toString(),
                credit.id().toString(),
                credit.settlementKey().provider(),
                credit.settlementKey().bankAccountReference(),
                credit.settlementKey().settlementReference(),
                credit.amount().amountMinor(),
                credit.amount().currency().getCurrencyCode()
        );
    }
}