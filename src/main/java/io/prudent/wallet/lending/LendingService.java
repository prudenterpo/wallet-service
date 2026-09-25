package io.prudent.wallet.lending;

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
    private static final String CONTRACT_OPERATION = "CONTRACT";

    private final JdbcClient jdbc;
    private final IrregularLoanCalculator calculator;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    @Transactional
    public BorrowerResponse createBorrower(BorrowerRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        insertBorrowerIfAbsent(organizationId, request);

        BorrowerResponse borrower = jdbc.sql("""
                        select id, external_reference, display_name
                        from borrower
                        where organization_id = :organizationId
                          and external_reference = :externalReference
                        """)
                .param("organizationId", organizationId)
                .param("externalReference", request.externalReference())
                .query((row, ignored) -> new BorrowerResponse(
                        row.getObject("id", UUID.class),
                        row.getString("external_reference"),
                        row.getString("display_name")))
                .single();
        if (!borrower.displayName().equals(request.displayName())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "BORROWER_REFERENCE_CONFLICT",
                    "Borrower reference already exists with different data");
        }
        return borrower;
    }

    public SimulationResponse simulate(SimulationRequest request) {
        return calculator.calculate(request);
    }

    @Transactional
    public ContractResponse createContract(String idempotencyKey, ContractRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, CONTRACT_OPERATION, idempotencyKey);
        SimulationResponse simulation = calculator.calculate(request.terms());
        String fingerprint = idempotency.fingerprint(
                new ContractCommand(request.borrowerId(), request.externalReference(), simulation));
        ContractResponse replay = idempotency.replay(
                organizationId,
                CONTRACT_OPERATION,
                idempotencyKey,
                fingerprint,
                ContractResponse.class);
        if (replay != null) {
            return replay;
        }

        requireBorrower(organizationId, request.borrowerId());

        UUID contractId = UUID.randomUUID();
        insertContract(organizationId, contractId, request, simulation);
        simulation.schedule().forEach(item -> insertInstallment(contractId, item));

        var response = new ContractResponse(
                contractId,
                request.externalReference(),
                "ACTIVE",
                simulation.totalPresentValue(),
                simulation.fee(),
                simulation.netAmount(),
                simulation.ruleVersion(),
                simulation.schedule());
        idempotency.remember(organizationId, CONTRACT_OPERATION, idempotencyKey, fingerprint, response);
        return response;
    }

    private void insertBorrowerIfAbsent(UUID organizationId, BorrowerRequest request) {
        jdbc.sql("""
                        insert into borrower(id, organization_id, external_reference, display_name, created_at)
                        values (:id, :organizationId, :externalReference, :displayName, :createdAt)
                        on conflict (organization_id, external_reference) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("externalReference", request.externalReference())
                .param("displayName", request.displayName())
                .param("createdAt", now())
                .update();
    }

    private void requireBorrower(UUID organizationId, UUID borrowerId) {
        boolean borrowerExists = jdbc.sql("""
                        select count(*)
                        from borrower
                        where id = :borrowerId
                          and organization_id = :organizationId
                        """)
                .param("borrowerId", borrowerId)
                .param("organizationId", organizationId)
                .query(Integer.class)
                .single() == 1;
        if (!borrowerExists) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "BORROWER_NOT_FOUND",
                    "Borrower was not found in this organization");
        }
    }

    private void insertContract(
            UUID organizationId,
            UUID contractId,
            ContractRequest request,
            SimulationResponse simulation) {
        jdbc.sql("""
                        insert into loan_contract(
                            id, organization_id, borrower_id, external_reference, rule_version,
                            disbursement_date, original_principal, annual_rate, fee, status, created_at
                        )
                        values (
                            :id, :organizationId, :borrowerId, :externalReference, :ruleVersion,
                            :disbursementDate, :originalPrincipal, :annualRate, :fee, 'ACTIVE', :createdAt
                        )
                        """)
                .param("id", contractId)
                .param("organizationId", organizationId)
                .param("borrowerId", request.borrowerId())
                .param("externalReference", request.externalReference())
                .param("ruleVersion", simulation.ruleVersion())
                .param("disbursementDate", request.terms().disbursementDate())
                .param("originalPrincipal", simulation.totalPresentValue())
                .param("annualRate", simulation.annualRate())
                .param("fee", simulation.fee())
                .param("createdAt", now())
                .update();
    }

    private void insertInstallment(UUID contractId, ScheduleItem installment) {
        jdbc.sql("""
                        insert into installment(
                            id, contract_id, installment_number, due_date, present_value, future_value, interest
                        )
                        values (:id, :contractId, :number, :dueDate, :presentValue, :futureValue, :interest)
                        """)
                .param("id", UUID.randomUUID())
                .param("contractId", contractId)
                .param("number", installment.number())
                .param("dueDate", installment.dueDate())
                .param("presentValue", installment.presentValue())
                .param("futureValue", installment.futureValue())
                .param("interest", installment.interest())
                .update();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private record ContractCommand(
            UUID borrowerId,
            String externalReference,
            SimulationResponse simulation) {}
}
