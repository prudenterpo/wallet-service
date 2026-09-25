alter table borrower add constraint uq_borrower_id_organization unique (id, organization_id);
alter table loan_contract add constraint uq_contract_id_organization unique (id, organization_id);
alter table loan_contract add constraint fk_contract_borrower_organization
    foreign key (borrower_id, organization_id) references borrower (id, organization_id);

alter table settlement add constraint uq_settlement_id_contract unique (id, contract_id);
alter table settlement add constraint fk_settlement_contract_organization
    foreign key (contract_id, organization_id) references loan_contract (id, organization_id);

alter table installment add constraint uq_installment_id_contract unique (id, contract_id);
alter table settlement_allocation add column contract_id uuid;
update settlement_allocation allocation
set contract_id = installment.contract_id
from installment
where installment.id = allocation.installment_id;
alter table settlement_allocation alter column contract_id set not null;
alter table settlement_allocation add constraint fk_allocation_settlement_contract
    foreign key (settlement_id, contract_id) references settlement (id, contract_id);
alter table settlement_allocation add constraint fk_allocation_installment_contract
    foreign key (installment_id, contract_id) references installment (id, contract_id);
