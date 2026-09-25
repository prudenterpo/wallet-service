package io.prudent.wallet.origination;

import io.prudent.wallet.lending.ContractResponse;
import io.prudent.wallet.lending.IrregularLoanCalculator;
import io.prudent.wallet.lending.ScheduleItem;
import io.prudent.wallet.lending.SimulationResponse;
import io.prudent.wallet.organization.OrganizationContext;
import io.prudent.wallet.platform.ApiException;
import io.prudent.wallet.platform.IdempotencyStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OriginationService {
    private record Proposal(
            UUID id,
            UUID borrowerId,
            String externalReference,
            String status,
            String ruleVersion,
            LocalDate disbursementDate,
            BigDecimal principal,
            BigDecimal annualRate,
            BigDecimal fee) {}

    private final JdbcClient jdbc;
    private final IrregularLoanCalculator calculator;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    @Transactional
    public ProposalResponse createProposal(String key, ProposalRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, "PROPOSAL", key);
        var simulation = calculator.calculate(request.terms());
        String fingerprint = idempotency.fingerprint(
                new ProposalCommand(request.borrowerId(), request.externalReference(), simulation));
        var replay = idempotency.replay(organizationId, "PROPOSAL", key, fingerprint, ProposalResponse.class);
        if (replay != null) return replay;

        requireBorrower(organizationId, request.borrowerId());
        if (simulation.totalPresentValue().signum() <= 0)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PROPOSAL_PRINCIPAL", "Proposal principal must be positive after rounding");

        UUID proposalId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
        jdbc.sql("insert into loan_proposal(id,organization_id,borrower_id,external_reference,rule_version,terms_fingerprint,disbursement_date,original_principal,annual_rate,fee,status,created_at) values (:id,:org,:borrower,:ref,:rule,:fingerprint,:date,:principal,:rate,:fee,'PROPOSED',:now)")
                .param("id", proposalId).param("org", organizationId).param("borrower", request.borrowerId())
                .param("ref", request.externalReference()).param("rule", simulation.ruleVersion())
                .param("fingerprint", idempotency.fingerprint(simulation)).param("date", request.terms().disbursementDate())
                .param("principal", simulation.totalPresentValue()).param("rate", simulation.annualRate())
                .param("fee", simulation.fee()).param("now", now).update();
        simulation.schedule().forEach(item -> insertProposalInstallment(proposalId, item));
        var response = new ProposalResponse(proposalId, request.borrowerId(), request.externalReference(), "PROPOSED",
                simulation.ruleVersion(), request.terms().disbursementDate(), simulation.totalPresentValue(),
                simulation.annualRate(), simulation.fee(), simulation.schedule());
        idempotency.remember(organizationId, "PROPOSAL", key, fingerprint, response);
        return response;
    }

    @Transactional
    public ContractResponse activate(UUID proposalId, String key, ActivationRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, "ACTIVATION", key);
        String fingerprint = idempotency.fingerprint(new ActivationCommand(proposalId, request));
        var replay = idempotency.replay(organizationId, "ACTIVATION", key, fingerprint, ContractResponse.class);
        if (replay != null) return replay;

        Proposal proposal = findForUpdate(organizationId, proposalId);
        if (!"PROPOSED".equals(proposal.status()))
            throw new ApiException(HttpStatus.CONFLICT, "PROPOSAL_ALREADY_ACTIVATED", "Proposal has already been activated");
        var schedule = proposalSchedule(proposal);
        UUID contractId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
        jdbc.sql("insert into loan_contract(id,organization_id,borrower_id,proposal_id,external_reference,rule_version,disbursement_date,original_principal,annual_rate,fee,status,created_at) values (:id,:org,:borrower,:proposal,:ref,:rule,:date,:principal,:rate,:fee,'ACTIVE',:now)")
                .param("id", contractId).param("org", organizationId).param("borrower", proposal.borrowerId())
                .param("proposal", proposal.id()).param("ref", request.contractExternalReference())
                .param("rule", proposal.ruleVersion()).param("date", proposal.disbursementDate())
                .param("principal", proposal.principal()).param("rate", proposal.annualRate())
                .param("fee", proposal.fee()).param("now", now).update();
        schedule.forEach(item -> insertContractInstallment(contractId, item));
        jdbc.sql("update loan_proposal set status='ACTIVATED',activated_at=:now where id=:id")
                .param("now", now).param("id", proposal.id()).update();
        var response = new ContractResponse(contractId, request.contractExternalReference(), "ACTIVE",
                proposal.principal(), proposal.fee(),
                IrregularLoanCalculator.money(proposal.principal().subtract(proposal.fee())),
                proposal.ruleVersion(), schedule);
        idempotency.remember(organizationId, "ACTIVATION", key, fingerprint, response);
        return response;
    }

    private void requireBorrower(UUID organizationId, UUID borrowerId) {
        boolean exists = jdbc.sql("select count(*) from borrower where id=:id and organization_id=:org")
                .param("id", borrowerId).param("org", organizationId).query(Integer.class).single() == 1;
        if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "BORROWER_NOT_FOUND", "Borrower was not found in this organization");
    }

    private Proposal findForUpdate(UUID organizationId, UUID proposalId) {
        return jdbc.sql("select id,borrower_id,external_reference,status,rule_version,disbursement_date,original_principal,annual_rate,fee from loan_proposal where id=:id and organization_id=:org for update")
                .param("id", proposalId).param("org", organizationId)
                .query((row, ignored) -> new Proposal(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getString(3), row.getString(4), row.getString(5), row.getObject(6, LocalDate.class),
                        row.getBigDecimal(7), row.getBigDecimal(8), row.getBigDecimal(9)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Proposal was not found in this organization"));
    }

    private java.util.List<ScheduleItem> proposalSchedule(Proposal proposal) {
        return jdbc.sql("select installment_number,due_date,present_value,future_value,interest from proposal_installment where proposal_id=:proposal order by installment_number")
                .param("proposal", proposal.id()).query((row, ignored) -> {
                    LocalDate dueDate = row.getObject(2, LocalDate.class);
                    int days = Math.toIntExact(ChronoUnit.DAYS.between(proposal.disbursementDate(), dueDate));
                    return new ScheduleItem(row.getInt(1), dueDate, days, row.getBigDecimal(3), row.getBigDecimal(4), row.getBigDecimal(5));
                }).list();
    }

    private void insertProposalInstallment(UUID proposalId, ScheduleItem item) {
        jdbc.sql("insert into proposal_installment(id,proposal_id,installment_number,due_date,present_value,future_value,interest) values (:id,:proposal,:number,:due,:present,:future,:interest)")
                .param("id", UUID.randomUUID()).param("proposal", proposalId).param("number", item.number())
                .param("due", item.dueDate()).param("present", item.presentValue()).param("future", item.futureValue())
                .param("interest", item.interest()).update();
    }

    private void insertContractInstallment(UUID contractId, ScheduleItem item) {
        jdbc.sql("insert into installment(id,contract_id,installment_number,due_date,present_value,future_value,interest) values (:id,:contract,:number,:due,:present,:future,:interest)")
                .param("id", UUID.randomUUID()).param("contract", contractId).param("number", item.number())
                .param("due", item.dueDate()).param("present", item.presentValue()).param("future", item.futureValue())
                .param("interest", item.interest()).update();
    }

    private record ActivationCommand(
            UUID proposalId,
            ActivationRequest request) {}

    private record ProposalCommand(
            UUID borrowerId,
            String externalReference,
            SimulationResponse simulation) {}
}
