CREATE INDEX ix_resolution_proposal_pending
    ON resolution_proposal (
                            tenant_id,
                            status,
                            proposed_at_utc,
                            id
        );