package io.prudent.wallet.lending;

import static io.prudent.wallet.lending.LendingModels.*;

import io.prudent.wallet.organization.OrganizationContext;
import io.prudent.wallet.platform.ApiException;
import io.prudent.wallet.platform.IdempotencyStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LendingService {
    private final JdbcClient jdbc;
    private final IrregularLoanCalculator calculator;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    @Transactional
    public BorrowerResponse createBorrower(BorrowerRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        UUID candidateId = UUID.randomUUID();
        jdbc.sql("insert into borrower(id, organization_id, external_reference, display_name, created_at) values (:id,:org,:ref,:name,:now) on conflict (organization_id, external_reference) do nothing")
                .param("id", candidateId).param("org", organizationId).param("ref", request.externalReference())
                .param("name", request.displayName()).param("now", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))).update();
        BorrowerResponse borrower = jdbc.sql("select id, external_reference, display_name from borrower where organization_id=:org and external_reference=:ref")
                .param("org", organizationId).param("ref", request.externalReference())
                .query((row, ignored) -> new BorrowerResponse(row.getObject("id", UUID.class), row.getString("external_reference"), row.getString("display_name")))
                .single();
        if (!borrower.displayName().equals(request.displayName())) {
            throw new ApiException(HttpStatus.CONFLICT, "BORROWER_REFERENCE_CONFLICT", "Borrower reference already exists with different data");
        }
        return borrower;
    }

    public SimulationResponse simulate(SimulationRequest request) { return calculator.calculate(request); }

    @Transactional
    public ContractResponse createContract(String idempotencyKey, ContractRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, "CONTRACT", idempotencyKey);
        SimulationResponse simulation = calculator.calculate(request.terms());
        String fingerprint = idempotency.fingerprint(new ContractCommand(request.borrowerId(), request.externalReference(), simulation));
        var replay = idempotency.replay(organizationId, "CONTRACT", idempotencyKey, fingerprint, ContractResponse.class);
        if (replay != null) return replay;
        boolean borrowerExists = jdbc.sql("select count(*) from borrower where id=:id and organization_id=:org")
                .param("id", request.borrowerId()).param("org", organizationId).query(Integer.class).single() == 1;
        if (!borrowerExists) throw new ApiException(HttpStatus.NOT_FOUND, "BORROWER_NOT_FOUND", "Borrower was not found in this organization");

        UUID contractId = UUID.randomUUID();
        jdbc.sql("insert into loan_contract(id,organization_id,borrower_id,external_reference,rule_version,disbursement_date,original_principal,annual_rate,fee,status,created_at) values (:id,:org,:borrower,:ref,:rule,:date,:principal,:rate,:fee,'ACTIVE',:now)")
                .param("id", contractId).param("org", organizationId).param("borrower", request.borrowerId())
                .param("ref", request.externalReference()).param("rule", simulation.ruleVersion())
                .param("date", request.terms().disbursementDate()).param("principal", simulation.totalPresentValue())
                .param("rate", simulation.annualRate()).param("fee", simulation.fee()).param("now", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))).update();
        simulation.schedule().forEach(item -> jdbc.sql("insert into installment(id,contract_id,installment_number,due_date,present_value,future_value,interest) values (:id,:contract,:number,:due,:present,:future,:interest)")
                .param("id", UUID.randomUUID()).param("contract", contractId).param("number", item.number())
                .param("due", item.dueDate()).param("present", item.presentValue()).param("future", item.futureValue())
                .param("interest", item.interest()).update());
        var response = new ContractResponse(contractId, request.externalReference(), "ACTIVE",
                simulation.totalPresentValue(), simulation.ruleVersion(), simulation.schedule());
        idempotency.remember(organizationId, "CONTRACT", idempotencyKey, fingerprint, response);
        return response;
    }

    private record ContractCommand(UUID borrowerId, String externalReference, SimulationResponse simulation) {}
}
