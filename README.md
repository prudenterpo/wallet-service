# Wallet Service

Wallet Service is an independent proof of concept for servicing irregular loans. It is a small Spring Boot modular monolith with no external business-system dependency.

## What it covers

- organization-scoped access with local API keys;
- borrower references and irregular-loan simulations;
- idempotent contract creation;
- historical contract and portfolio positions;
- FIFO amortization with PostgreSQL-backed idempotency;
- OpenAPI, PostgreSQL, Flyway and Docker Compose.

Financial calculations use the explicit rule version POC-SIMPLE-ACT-365-V1: simple interest, actual elapsed days over 365, installment values supplied as future values and HALF_EVEN rounding for exposed monetary amounts. Taxes, penalties, monetary correction and provider-specific rules are outside this POC.

## Run locally

Requirements: Docker with Compose.

    docker compose up --build

The application starts at http://localhost:8080. OpenAPI is available at http://localhost:8080/v3/api-docs, Swagger UI at http://localhost:8080/swagger-ui.html and health at http://localhost:8080/actuator/health.

Local organization keys:

- local-demo-key
- local-isolation-key

To stop the application and remove its disposable database:

    docker compose down --volumes

## Main API journey

Every API request requires X-Organization-Key. Contract creation and amortization also require Idempotency-Key.

1. POST /api/v1/borrowers
2. POST /api/v1/simulations
3. POST /api/v1/contracts
4. GET /api/v1/contracts/{contractId}/position?asOf=YYYY-MM-DD
5. POST /api/v1/contracts/{contractId}/amortizations
6. GET /api/v1/portfolio/position?asOf=YYYY-MM-DD

The generated OpenAPI document is the authoritative request and response reference.

## Verify

Use Java 25 and Docker:

    sdk env install
    ./mvnw verify

The test suite runs financial reference cases and a complete HTTP journey against PostgreSQL 18 with Flyway. It covers concurrent idempotency, conflicting replays, organization isolation and portfolio positions.

## Boundaries

This is an executable POC, not a production-ready lending platform. It does not include credit analysis, approval workflow, KYC, signatures, payoff, reversals, billing or remittance files, accounting exports, production authentication, data migration, messaging, cloud infrastructure, deployment or release automation.
