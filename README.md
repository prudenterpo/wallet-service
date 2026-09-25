# Wallet Service

Wallet Service is an independent proof of concept for originating and servicing irregular loans. It is a small Spring Boot modular monolith with no external business-system dependency.

## What it covers

- organization-scoped access with local API keys;
- borrower references and irregular-loan simulations;
- idempotent proposal creation and contract activation;
- idempotent direct contract creation;
- historical contract and portfolio positions;
- FIFO amortization, nominal payoff and latest-amortization reversal;
- servicing history and local portfolio reconciliation;
- OpenAPI, PostgreSQL, Flyway and Docker Compose.

Financial calculations use the explicit rule version POC-SIMPLE-ACT-365-V1: simple interest, actual elapsed days over 365, installment values supplied as future values and HALF_EVEN rounding for exposed monetary amounts. Payoff uses the nominal outstanding balance. Taxes, penalties, monetary correction, partial reversal and provider-specific rules are outside this POC.

The service applies cash against remaining future value, FIFO by due date. Position `outstanding` is remaining future value, not accrued principal. `remainingPresentValue` is the unpaid fraction of each installment's original present value (`present * remainingFuture / originalFuture`). Origination `fee` reduces `netAmount` at simulation and contract creation; it is not amortized. Amortization `discount` reduces the future-value amount applied and `addition` increases it beyond cash received. A schedule may contain at most 120 installments.

## Run locally

Requirements: Docker with Compose.

    docker compose up --build

The application starts at http://localhost:8080. OpenAPI is available at http://localhost:8080/v3/api-docs, Swagger UI at http://localhost:8080/swagger-ui.html and health at http://localhost:8080/actuator/health. Actuator exposes only health. Local API keys in Compose are for a disposable database; do not reuse them outside this machine.

Local organization keys:

- local-demo-key
- local-isolation-key

To stop the application and remove its disposable database:

    docker compose down --volumes

## Main API journey

Every API request requires X-Organization-Key. Mutating financial operations also require Idempotency-Key.

1. POST /api/v1/borrowers
2. POST /api/v1/simulations
3. POST /api/v1/proposals
4. POST /api/v1/proposals/{proposalId}/activation
5. GET /api/v1/contracts/{contractId}/position?asOf=YYYY-MM-DD
6. POST /api/v1/contracts/{contractId}/amortizations
7. POST /api/v1/contracts/{contractId}/payoffs
8. POST /api/v1/contracts/{contractId}/amortizations/{settlementId}/reversal
9. GET /api/v1/contracts/{contractId}/history
10. GET /api/v1/portfolio/position?asOf=YYYY-MM-DD
11. GET /api/v1/portfolio/reconciliation?asOf=YYYY-MM-DD

The generated OpenAPI document is the authoritative request and response reference.

## Verify

Use Java 25 and Docker:

    sdk env install
    ./mvnw verify

The test suite runs financial reference cases and complete HTTP journeys against PostgreSQL 18 with Flyway. It covers concurrent idempotency, conflicting replays, organization isolation, temporal servicing rules and reconciliation invariants.

## Boundaries

This is an executable POC, not a production-ready lending platform. It does not include credit analysis, approval workflow, KYC, signatures, billing or remittance files, accounting exports, production authentication, data migration, messaging, cloud infrastructure, deployment or release automation.
