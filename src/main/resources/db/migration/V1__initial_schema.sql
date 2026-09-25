create table organization (
    id uuid primary key,
    name varchar(120) not null,
    api_key_hash char(64) not null unique,
    created_at timestamptz not null
);
create table borrower (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    external_reference varchar(100) not null,
    display_name varchar(160) not null,
    created_at timestamptz not null,
    unique (organization_id, external_reference)
);
create table loan_contract (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    borrower_id uuid not null references borrower(id),
    external_reference varchar(100) not null,
    rule_version varchar(40) not null,
    disbursement_date date not null,
    original_principal numeric(19,2) not null check (original_principal > 0),
    annual_rate numeric(12,8) not null check (annual_rate >= 0),
    fee numeric(19,2) not null check (fee >= 0),
    status varchar(20) not null,
    created_at timestamptz not null,
    unique (organization_id, external_reference)
);
create table installment (
    id uuid primary key,
    contract_id uuid not null references loan_contract(id),
    installment_number integer not null check (installment_number > 0),
    due_date date not null,
    present_value numeric(19,2) not null check (present_value >= 0),
    future_value numeric(19,2) not null check (future_value > 0),
    interest numeric(19,2) not null check (interest >= 0),
    unique (contract_id, installment_number)
);
create table settlement (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    contract_id uuid not null references loan_contract(id),
    effective_date date not null,
    submitted_amount numeric(19,2) not null check (submitted_amount > 0),
    discount numeric(19,2) not null check (discount >= 0),
    addition numeric(19,2) not null check (addition >= 0),
    allocated_amount numeric(19,2) not null check (allocated_amount > 0),
    payment_method varchar(40) not null,
    accounting_reference varchar(100) not null,
    created_at timestamptz not null
);
create table settlement_allocation (
    settlement_id uuid not null references settlement(id),
    installment_id uuid not null references installment(id),
    amount numeric(19,2) not null check (amount > 0),
    primary key (settlement_id, installment_id)
);
create table idempotency_record (
    organization_id uuid not null references organization(id),
    operation varchar(40) not null,
    idempotency_key varchar(120) not null,
    request_fingerprint char(64) not null,
    response_json jsonb not null,
    created_at timestamptz not null,
    primary key (organization_id, operation, idempotency_key)
);
create index idx_contract_org on loan_contract(organization_id);
create index idx_installment_contract_due on installment(contract_id, due_date, installment_number);
create index idx_settlement_contract_effective on settlement(contract_id, effective_date);
