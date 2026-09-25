# Wallet Service POC

Wallet Service is a deliberately small, independent proof of concept for one irregular-loan servicing vertical. It has no AgroForte runtime dependency and does not claim full FacCred compatibility.

The product and parity artifacts remain in the sync-brain vault under projects-af/wallet-engine. They are not duplicated in this repository.

## Included vertical

- organization-scoped access through a local POC API key;
- minimum borrower reference;
- deterministic irregular-loan simulation;
- idempotent contract creation;
- historical contract and portfolio position;
- FIFO amortization with PostgreSQL-backed idempotency;
- generated OpenAPI and Swagger UI;
- PostgreSQL 18 schema managed by Flyway;
- one Docker Compose command for local execution.

## Explicit financial assumptions

The rule version is POC-SIMPLE-ACT-365-V1. It exists to make assumptions visible and configurable through versioned code, not to infer undocumented provider behavior.

- each supplied installment amount is its future value;
- present value uses simple interest with actual elapsed days divided by 365;
- working scale is 12 and exposed money is rounded to 2 decimals with HALF_EVEN;
- no holiday or business-day adjustment is applied;
- fee is reported separately and subtracted only from the displayed net amount;
- amortization allocation is amount plus addition minus discount;
- allocation is FIFO by due date and installment number;
- amortizations for one contract are accepted only in nondecreasing effective-date order;
- taxes, penalties, correction, reversal and early-payoff rules are unsupported.

No sanitized FacCred request and response sample was found in the available wallet-engine artifacts. Therefore the included reference test proves the declared formula only. FacCred parity remains unverified and must not be inferred from a passing build.

## Run locally

Requirements: Docker with Compose.

    docker compose up --build

For a host-side Maven build, install SDKMAN and activate the repository JDK first:

    sdk env install
    ./mvnw verify

The local bootstrap creates two synthetic organizations:

- primary key: local-demo-key
- isolation key: local-isolation-key

OpenAPI is available at http://localhost:8080/v3/api-docs and Swagger UI at http://localhost:8080/swagger-ui.html. Health is available at http://localhost:8080/actuator/health.

Stop and remove the disposable database with:

    docker compose down --volumes

## API journey

All API calls require X-Organization-Key. Contract creation and amortization also require Idempotency-Key.

1. POST /api/v1/borrowers
2. POST /api/v1/simulations
3. POST /api/v1/contracts
4. GET /api/v1/contracts/{contractId}/position?asOf=YYYY-MM-DD
5. POST /api/v1/contracts/{contractId}/amortizations
6. GET /api/v1/portfolio/position?asOf=YYYY-MM-DD

The generated OpenAPI document is the request and response reference for the running implementation.

## Verification

Use Java 25 and Docker:

    ./mvnw verify

The intentionally small suite contains one financial reference scenario and one end-to-end BDD-style HTTP scenario. The HTTP scenario starts PostgreSQL 18, applies Flyway, proves the complete journey, submits the same amortization concurrently, rejects a conflicting replay, reconciles positions and denies cross-organization access.

## POC boundaries

This repository does not include deployment, release automation, provider credentials, production authentication, migration tooling, queues, outbox, event sourcing, cloud resources or a compatibility adapter. Local API keys and bootstrap fixtures are for POC execution only.
