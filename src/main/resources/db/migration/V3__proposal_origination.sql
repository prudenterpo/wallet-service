create table loan_proposal (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    borrower_id uuid not null references borrower(id),
    external_reference varchar(100) not null,
    rule_version varchar(40) not null,
    terms_fingerprint char(64) not null,
    disbursement_date date not null,
    original_principal numeric(19,2) not null check (original_principal > 0),
    annual_rate numeric(12,8) not null check (annual_rate >= 0),
    fee numeric(19,2) not null check (fee >= 0),
    status varchar(20) not null check (status in ('PROPOSED', 'ACTIVATED')),
    created_at timestamptz not null,
    activated_at timestamptz,
    unique (organization_id, external_reference),
    unique (id, organization_id),
    foreign key (borrower_id, organization_id) references borrower(id, organization_id)
);

create table proposal_installment (
    id uuid primary key,
    proposal_id uuid not null references loan_proposal(id),
    installment_number integer not null check (installment_number > 0),
    due_date date not null,
    present_value numeric(19,2) not null check (present_value >= 0),
    future_value numeric(19,2) not null check (future_value > 0),
    interest numeric(19,2) not null check (interest >= 0),
    unique (proposal_id, installment_number)
);

alter table loan_contract add column proposal_id uuid references loan_proposal(id);
create unique index uk_contract_proposal on loan_contract(proposal_id) where proposal_id is not null;
alter table loan_contract add constraint fk_contract_proposal_organization
    foreign key (proposal_id, organization_id) references loan_proposal(id, organization_id);
create index idx_proposal_org on loan_proposal(organization_id);
create index idx_proposal_installment on proposal_installment(proposal_id, due_date, installment_number);
