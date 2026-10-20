# ReconBridge

Settlement Reconciliation & Exception Management

ReconBridge is a Java backend portfolio project that will compare
internal payment records, processor settlement reports, and bank
credits to identify discrepancies and support controlled investigation.

## Project status

Foundation stage.

Implemented:
- Spring Boot application bootstrap
- Application health endpoint
- Generated application-context test
- Immutable monetary values with currency validation and overflow protection
- Tenant-aware and provider-aware matching keys
- Source types and matching outcome definitions
- Domain unit tests covering settlement calculations and matching-key behavior
- Payment and settlement domain models with monetary validation
- Strategy-based exact-reference payment matching
- HashMap candidate indexing that preserves ambiguous references
- Detection of missing counterparts, amount mismatches, and currency mismatches
- Duplicate input safeguards and immutable matching results
- Unit tests for matching decisions and settlement validation
- Payment reconciliation reports with counts for every matching outcome
- Detection of settlement lines without corresponding internal payments
- Separate reporting of unresolved payments and orphan settlement lines
- Immutable report snapshots and explicit empty-input behavior
- Unit tests covering mixed outcomes and orphan detection

Planned:
- Payment, settlement, and bank-file imports
- Exact-reference matching and settlement batch reconciliation
- Duplicate detection and idempotent processing
- Tenant isolation with Spring Security and Keycloak
- Exception investigation and independent approval
- Audit history and versioned database migrations
- Kafka event delivery with a transactional outbox
- Container-based integration tests
- Kubernetes deployment exercises

## Problem statement

Payment success does not by itself prove that the expected settlement
reached the bank. Fees, delayed credits, missing records, duplicates,
and incorrect amounts can cause differences between systems.

ReconBridge will preserve source evidence, explain matching decisions,
and track unresolved differences through an auditable review process.

## Example

- Successful payments: INR 3,000
- Processor fees: INR 60
- Expected bank credit: INR 2,940
- Actual bank credit: INR 2,900
- Difference to investigate: INR 40

## Current technology

- Java 21
- Spring Boot
- Spring Web
- Jakarta Bean Validation
- Spring Boot Actuator
- Maven Wrapper

## Planned technology

MySQL, Flyway, Spring Batch, Spring Security, Keycloak,
Apache Kafka, Docker, Testcontainers, and Kubernetes.

## Run locally

Prerequisite: JDK 21.

Windows PowerShell:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

Health endpoint:

```http
GET http://localhost:8083/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

## Development roadmap

1. Domain models and matching tests
2. Persistence and restartable imports
3. Reconciliation rules and allocation safeguards
4. Tenant security and exception workflows
5. Kafka messaging and failure-recovery tests
6. Monitoring, CI, and a reproducible demo
7. Kubernetes deployment and recovery exercises

Tenant validation will be implemented before business endpoints
are exposed.

## Documentation

See the `docs` directory for the project requirements and architecture.

## Scope

This is a learning and portfolio project using synthetic data.
It does not initiate real payments or connect to live bank accounts.



