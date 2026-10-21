# ReconBridge

Settlement Reconciliation & Exception Management

ReconBridge is a Java backend portfolio project that will compare
internal payment records, processor settlement reports, and bank
credits to identify discrepancies and support controlled investigation.

## Problem statement

Payment success does not by itself prove that the expected settlement
reached the bank. Fees, delayed credits, missing records, duplicates,
and incorrect amounts can cause differences between systems.

ReconBridge will preserve source evidence, explain matching decisions,
and track unresolved differences through an auditable review process.

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
- Settlement batch validation against declared net totals
- Exact-reference settlement-to-bank credit matching
- Bank-account-aware settlement identities
- Signed differences for shortfalls and excess credits
- Ambiguous bank-credit detection and currency mismatch handling
- Safeguards against settlement-line reuse across supplied batches
- Combined payment-to-settlement and settlement-to-bank reconciliation
- Shared settlement evidence across both reconciliation passes
- Detection of bank credits without corresponding settlement batches
- Independent outcome summaries for payments and settlement batches
- Overall reconciliation status requiring both passes and orphan checks to pass
- Tests proving that success in one pass cannot hide discrepancies in the other
- MySQL application and test databases with separate credentials
- Flyway migrations for tenant and import-batch tables
- Database constraints for import ownership and duplicate content
- Integration tests against MySQL for migrations and persistence
- JPA mappings for tenants and import batches
- Tenant-scoped import lookup and content-fingerprint lookup
- Version field mapped for future optimistic concurrency control
- Repository integration tests for persistence, tenant isolation,
  and duplicate-content constraints
- Guarded import lifecycle transitions with terminal READY and REJECTED states
- Explicit retry transition for operationally failed imports
- Optimistic locking for versioned import updates
- Deterministic MySQL tests using overlapping transactions
- Verification that stale workers cannot overwrite committed import state
- Transactional application service for import lifecycle operations
- Tenant-scoped reads and transitions
- Explicit expected-version checks alongside JPA optimistic locking
- Immutable service snapshots containing persisted versions
- Integration tests for committed transitions, stale requests,
  tenant isolation, and rejected operations
- Keycloak JWT authentication with issuer, timestamp, audience,
    and tenant-claim validation
- Administrator-controlled tenant identity
- Explicit mapping of application realm roles
- Stateless security with endpoints denied unless explicitly permitted
- Authenticated identity endpoint at GET /api/me
- Automated claim-validation and HTTP authorization tests

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

## Database configuration

Application:
- Schema: reconbridge
- User: reconbridge_app
- Password environment variable: RECONBRIDGE_DB_PASSWORD

Tests:
- Schema: reconbridge_test
- User: reconbridge_test_app
- Password environment variable: RECONBRIDGE_TEST_DB_PASSWORD

MySQL must be running before application startup or integration tests.
Flyway applies migrations automatically.


## Example

- Successful payments: INR 3,000
- Processor fees: INR 60
- Expected bank credit: INR 2,940
- Actual bank credit: INR 2,900
- Difference to investigate: INR 40

### Authentication and tenant identity

- Secured the API using Spring Security OAuth2 Resource Server and Keycloak.
- Validates JWT issuer, audience, and tenant identity.
- Maps supported Keycloak realm roles to application authorities.
- Provides GET /api/me to return the authenticated subject and tenant ID.
- Includes automated security tests and manual Postman verification.

### Import registration

- Added POST /api/imports, restricted to the IMPORTER role.
- Derives tenant identity from the validated Keycloak JWT.
- Validates source metadata, business date, and SHA-256 hash format.
- Registers imports in RECEIVED status with an initial row count of zero.
- Returns 201 Created with a Location header.
- Rejects duplicate content within the same tenant, source type,
  and source system with 409 Conflict.
- Uses a database uniqueness constraint to protect concurrent registration.
- Allows separate tenants to register identical content independently.
- Includes integration tests for validation, authorization, tenant isolation,
  transaction rollback, and competing registrations.

Registration currently stores metadata only. File upload, actual content
hash verification, CSV parsing, and reconciliation processing are upcoming.

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

### Project documentation

See [Project requirements and architecture](docs/ReconBridge_Project_Requirements_and_Architecture.docx)
for architecture diagrams, implementation progress, and a separate PowerShell
command reference with explanations.

## Scope

This is a learning and portfolio project using synthetic data.
It does not initiate real payments or connect to live bank accounts.

### Tenant-scoped import retrieval

- Added GET /api/imports/{importId}.
- Derives tenant identity from the validated JWT tenant_id claim.
- Retrieves imports using both tenant ID and import ID.
- Returns 404 for missing imports and imports owned by another tenant.
- Ignores caller-supplied tenant IDs in query parameters and headers.
- Returns structured errors for missing imports and invalid UUIDs.
- Automated test suite: 100 tests passed.
- Manually verified with real Keycloak tokens: own-tenant access (200), cross-tenant access (404),
    missing authentication (401), and malformed import IDs (400).



