# Wallet Service POC

Wallet Service is a deliberately small, independent proof of concept for one irregular-loan servicing vertical. It has no AgroForte runtime dependency and does not claim full FacCred compatibility.

The product and parity artifacts remain in the sync-brain vault under projects-af/wallet-engine. They are not duplicated in this repository.

## Included capabilities

- organization-scoped access through a local POC API key;
- minimum borrower reference;
- deterministic irregular-loan simulation;
- idempotent proposal creation and proposal-to-contract activation;
- idempotent direct contract creation;
- historical contract and portfolio position;
- FIFO amortization with PostgreSQL-backed idempotency;
- nominal early payoff;
- latest-amortization reversal;
- servicing history and local portfolio reconciliation;
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
- early payoff uses the nominal outstanding balance with no discount or projected interest;
- only the latest non-reversed amortization can be reversed, and reversals are integral;
- all servicing commands are accepted only in nondecreasing effective-date order;
- taxes, penalties, monetary correction, partial reversal and provider-specific payoff rules are unsupported.

No sanitized FacCred request and response sample was found in the available wallet-engine artifacts. Therefore the included reference test proves the declared formula only. FacCred parity remains unverified and must not be inferred from a passing build.

## Run locally

Requirements: Docker with Compose.

    docker compose up --build

For a host-side Maven build, install SDKMAN and activate the repository JDK first:

    sdk env install
    ./mvnw verify

The committed VS Code workspace settings point the Java language server to a project-specific Maven cache under the user's cache directory. Java 25 is selected by the Maven project and the SDKMAN environment. After the first checkout, run Java: Clean Java Language Server Workspace if the editor previously imported the project with another JDK or Maven repository.

The local bootstrap creates two synthetic organizations:

- primary key: local-demo-key
- isolation key: local-isolation-key

OpenAPI is available at http://localhost:8080/v3/api-docs and Swagger UI at http://localhost:8080/swagger-ui.html. Health is available at http://localhost:8080/actuator/health.

Stop and remove the disposable database with:

    docker compose down --volumes

## API journey

All API calls require X-Organization-Key. Proposal creation, activation, contract creation, amortization, payoff and reversal also require Idempotency-Key.

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

The generated OpenAPI document is the request and response reference for the running implementation.

## Verification

Use Java 25 and Docker:

    ./mvnw verify

The intentionally small suite contains financial reference scenarios and BDD-style HTTP journeys. They start PostgreSQL 18, apply Flyway, exercise origination and servicing, prove concurrent idempotency and conflicting replay behavior, reconcile positions and deny cross-organization access.

## POC boundaries

This repository does not include credit analysis, approval workflow, KYC, signatures, billing files, remittance, accounting export, deployment, release automation, provider credentials, production authentication, migration tooling, queues, outbox, event sourcing, cloud resources or a compatibility adapter. History covers servicing actions recorded by this POC, rather than reconstructing the entire contract lifecycle. Reconciliation checks local accounting invariants only. Local API keys and bootstrap fixtures are for POC execution only.
