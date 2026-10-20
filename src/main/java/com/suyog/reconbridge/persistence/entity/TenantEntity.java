package com.suyog.reconbridge.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tenant")
public class TenantEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            updatable = false
    )
    private UUID id;

    @Column(
            name = "name",
            nullable = false,
            length = 150
    )
    private String name;

    protected TenantEntity() {
        // Required by JPA.
    }

    public TenantEntity(UUID id, String name) {
        this.id = Objects.requireNonNull(
                id,
                "id must not be null"
        );

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Tenant name must not be blank"
            );
        }

        String normalizedName = name.trim();

        if (normalizedName.length() > 150) {
            throw new IllegalArgumentException(
                    "Tenant name must not exceed 150 characters"
            );
        }

        this.name = normalizedName;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}