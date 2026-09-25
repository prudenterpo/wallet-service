alter table settlement
    add column settlement_type varchar(20) not null default 'AMORTIZATION'
        check (settlement_type in ('AMORTIZATION', 'PAYOFF'));

create table settlement_reversal (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    contract_id uuid not null,
    settlement_id uuid not null,
    effective_date date not null,
    reason varchar(200) not null,
    created_at timestamptz not null,
    unique (settlement_id),
    foreign key (contract_id, organization_id) references loan_contract(id, organization_id),
    foreign key (settlement_id, contract_id) references settlement(id, contract_id)
);

create function enforce_reversal_effective_date() returns trigger as $$
begin
    if new.effective_date < (select effective_date from settlement where id = new.settlement_id) then
        raise exception 'reversal effective date cannot precede settlement effective date'
            using errcode = '23514';
    end if;
    return new;
end;
$$ language plpgsql;

create constraint trigger settlement_reversal_effective_date_check
    after insert or update of settlement_id, effective_date on settlement_reversal
    deferrable initially immediate
    for each row execute function enforce_reversal_effective_date();

create table audit_entry (
    id uuid primary key,
    organization_id uuid not null references organization(id),
    contract_id uuid not null,
    action varchar(40) not null,
    entity_id uuid not null,
    effective_date date not null,
    processed_at timestamptz not null,
    metadata jsonb not null,
    foreign key (contract_id, organization_id) references loan_contract(id, organization_id)
);

create index idx_reversal_contract_effective
    on settlement_reversal(contract_id, effective_date);
create index idx_audit_contract_processed
    on audit_entry(contract_id, processed_at, id);
