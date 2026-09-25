package io.prudent.wallet.servicing;

import static io.prudent.wallet.lending.IrregularLoanCalculator.money;
import static io.prudent.wallet.lending.IrregularLoanCalculator.remainingInterest;
import static io.prudent.wallet.lending.IrregularLoanCalculator.remainingPresentValue;
import static io.prudent.wallet.servicing.ServicingModels.*;

import io.prudent.wallet.organization.OrganizationContext;
import io.prudent.wallet.platform.ApiException;
import io.prudent.wallet.platform.IdempotencyStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServicingService {
    private record InstallmentBalance(UUID id, int number, LocalDate dueDate, BigDecimal present, BigDecimal scheduled, BigDecimal paid) {
        BigDecimal outstanding() { return money(scheduled.subtract(paid)); }
        BigDecimal remainingPresent() { return remainingPresentValue(present, scheduled, outstanding()); }
    }
    private record ContractSummary(UUID id, String reference, BigDecimal principal, LocalDate disbursementDate) {}
    private record PortfolioRow(UUID contractId, BigDecimal principal, BigDecimal present, BigDecimal scheduled, BigDecimal paid) {}

    private final JdbcClient jdbc;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    @Transactional
    public AmortizationResponse amortize(UUID contractId, String key, AmortizationRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, "AMORTIZATION", key);
        String fingerprint = idempotency.fingerprint(new Command(contractId, request.effectiveDate(), money(request.amount()),
                money(request.discount()), money(request.addition()), request.paymentMethod(), request.accountingReference()));
        var replay = idempotency.replay(organizationId, "AMORTIZATION", key, fingerprint, AmortizationResponse.class);
        if (replay != null) return replay;

        ContractSummary contract = jdbc.sql("select id,external_reference,original_principal,disbursement_date from loan_contract where id=:id and organization_id=:org for update")
                .param("id", contractId).param("org", organizationId).query((row, ignored) ->
                        new ContractSummary(row.getObject(1, UUID.class), row.getString(2), row.getBigDecimal(3), row.getObject(4, LocalDate.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND", "Contract was not found in this organization"));
        validateReferenceDate(contract, request.effectiveDate());
        @Nullable LocalDate latestEffectiveDate = jdbc.sql("select max(effective_date) from settlement where contract_id=:contract")
                .param("contract", contract.id()).query(LocalDate.class).optional().orElse(null);
        if (latestEffectiveDate != null && request.effectiveDate().isBefore(latestEffectiveDate))
            throw new ApiException(HttpStatus.CONFLICT, "OUT_OF_ORDER_EFFECTIVE_DATE", "Amortizations must be submitted in nondecreasing effective-date order");
        BigDecimal allocatedAmount = money(request.amount().add(request.addition()).subtract(request.discount()));
        if (allocatedAmount.signum() <= 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMORTIZATION_TOTAL", "Amount plus addition minus discount must be positive");
        var balances = balances(contract.id(), request.effectiveDate());
        BigDecimal outstanding = sumOutstanding(balances);
        if (allocatedAmount.compareTo(outstanding) > 0)
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "AMOUNT_EXCEEDS_BALANCE", "Allocated amount exceeds remaining future value on the effective date");

        UUID settlementId = UUID.randomUUID();
        jdbc.sql("insert into settlement(id,organization_id,contract_id,effective_date,submitted_amount,discount,addition,allocated_amount,payment_method,accounting_reference,created_at) values (:id,:org,:contract,:date,:amount,:discount,:addition,:allocated,:method,:accounting,:now)")
                .param("id", settlementId).param("org", organizationId).param("contract", contract.id())
                .param("date", request.effectiveDate()).param("amount", money(request.amount()))
                .param("discount", money(request.discount())).param("addition", money(request.addition()))
                .param("allocated", allocatedAmount).param("method", request.paymentMethod())
                .param("accounting", request.accountingReference()).param("now", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))).update();
        BigDecimal remaining = allocatedAmount;
        var allocations = new ArrayList<Allocation>();
        for (var balance : balances) {
            if (remaining.signum() == 0) break;
            BigDecimal allocation = remaining.min(balance.outstanding());
            if (allocation.signum() > 0) {
                jdbc.sql("insert into settlement_allocation(settlement_id,installment_id,contract_id,amount) values (:settlement,:installment,:contract,:amount)")
                        .param("settlement", settlementId).param("installment", balance.id()).param("contract", contract.id())
                        .param("amount", allocation).update();
                allocations.add(new Allocation(balance.id(), balance.number(), allocation));
                remaining = money(remaining.subtract(allocation));
            }
        }
        var after = balances(contract.id(), request.effectiveDate());
        BigDecimal remainingBalance = sumOutstanding(after);
        BigDecimal remainingPresent = sumRemainingPresent(after);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (totalOutstanding(contract.id()).signum() == 0 && !request.effectiveDate().isAfter(today))
            jdbc.sql("update loan_contract set status='SETTLED' where id=:id").param("id", contract.id()).update();
        var response = new AmortizationResponse(settlementId, contract.id(), request.effectiveDate(),
                money(request.amount()), allocatedAmount, remainingBalance, remainingPresent, allocations);
        idempotency.remember(organizationId, "AMORTIZATION", key, fingerprint, response);
        return response;
    }

    public ContractPosition contractPosition(UUID contractId, LocalDate asOf) {
        UUID organizationId = OrganizationContext.requiredId();
        ContractSummary contract = jdbc.sql("select id,external_reference,original_principal,disbursement_date from loan_contract where id=:id and organization_id=:org")
                .param("id", contractId).param("org", organizationId).query((row, ignored) ->
                        new ContractSummary(row.getObject(1, UUID.class), row.getString(2), row.getBigDecimal(3), row.getObject(4, LocalDate.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND", "Contract was not found in this organization"));
        validateReferenceDate(contract, asOf);
        var items = balances(contractId, asOf);
        BigDecimal scheduled = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal originalInterest = BigDecimal.ZERO;
        BigDecimal remainingPresent = BigDecimal.ZERO;
        for (var item : items) {
            scheduled = scheduled.add(item.scheduled());
            paid = paid.add(item.paid());
            originalInterest = originalInterest.add(item.scheduled().subtract(item.present()));
            remainingPresent = remainingPresent.add(item.remainingPresent());
        }
        scheduled = money(scheduled);
        paid = money(paid);
        originalInterest = money(originalInterest);
        remainingPresent = money(remainingPresent);
        BigDecimal outstanding = money(scheduled.subtract(paid));
        var positions = items.stream().map(item -> new InstallmentPosition(item.id(), item.number(), item.dueDate(),
                money(item.present()), money(item.scheduled()), money(item.paid()), item.outstanding(), item.remainingPresent())).toList();
        return new ContractPosition(contract.id(), contract.reference(), asOf, outstanding.signum() == 0 ? "SETTLED" : "ACTIVE",
                money(contract.principal()), originalInterest, scheduled, paid, outstanding, remainingPresent,
                remainingInterest(outstanding, remainingPresent), positions);
    }

    public PortfolioPosition portfolioPosition(LocalDate asOf) {
        UUID organizationId = OrganizationContext.requiredId();
        var rows = jdbc.sql("""
                select c.id, c.original_principal, i.present_value, i.future_value,
                       coalesce((select sum(a.amount) from settlement_allocation a join settlement s on s.id = a.settlement_id
                                 where a.installment_id = i.id and s.effective_date <= :asOf), 0)
                from loan_contract c
                join installment i on i.contract_id = c.id
                where c.organization_id = :org and c.disbursement_date <= :asOf
                """)
                .param("asOf", asOf).param("org", organizationId)
                .query((row, ignored) -> new PortfolioRow(row.getObject(1, UUID.class), row.getBigDecimal(2),
                        row.getBigDecimal(3), row.getBigDecimal(4), row.getBigDecimal(5)))
                .list();
        var contractIds = new HashSet<UUID>();
        BigDecimal originalPrincipal = BigDecimal.ZERO;
        BigDecimal scheduled = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal remainingPresent = BigDecimal.ZERO;
        for (var row : rows) {
            if (contractIds.add(row.contractId())) {
                originalPrincipal = originalPrincipal.add(row.principal());
            }
            scheduled = scheduled.add(row.scheduled());
            paid = paid.add(row.paid());
            remainingPresent = remainingPresent.add(remainingPresentValue(row.present(), row.scheduled(), money(row.scheduled().subtract(row.paid()))));
        }
        scheduled = money(scheduled);
        paid = money(paid);
        remainingPresent = money(remainingPresent);
        BigDecimal outstanding = money(scheduled.subtract(paid));
        return new PortfolioPosition(asOf, contractIds.size(), money(originalPrincipal), scheduled, paid, outstanding,
                remainingPresent, remainingInterest(outstanding, remainingPresent));
    }

    private java.util.List<InstallmentBalance> balances(UUID contractId, LocalDate asOf) {
        return jdbc.sql("select i.id,i.installment_number,i.due_date,i.present_value,i.future_value,coalesce(sum(a.amount) filter (where s.effective_date<=:asOf),0) from installment i left join settlement_allocation a on a.installment_id=i.id left join settlement s on s.id=a.settlement_id where i.contract_id=:contract group by i.id,i.installment_number,i.due_date,i.present_value,i.future_value order by i.due_date,i.installment_number")
                .param("asOf", asOf).param("contract", contractId).query((row, ignored) ->
                        new InstallmentBalance(row.getObject(1, UUID.class), row.getInt(2), row.getObject(3, LocalDate.class),
                                row.getBigDecimal(4), row.getBigDecimal(5), row.getBigDecimal(6))).list();
    }

    private BigDecimal totalOutstanding(UUID contractId) {
        return money(jdbc.sql("select coalesce(sum(i.future_value),0)-coalesce((select sum(a.amount) from settlement_allocation a join settlement s on s.id=a.settlement_id join installment x on x.id=a.installment_id where x.contract_id=:contract),0) from installment i where i.contract_id=:contract")
                .param("contract", contractId).query(BigDecimal.class).single());
    }

    private BigDecimal sumOutstanding(java.util.List<InstallmentBalance> balances) {
        BigDecimal result = BigDecimal.ZERO;
        for (var balance : balances) result = result.add(balance.outstanding());
        return money(result);
    }

    private BigDecimal sumRemainingPresent(java.util.List<InstallmentBalance> balances) {
        BigDecimal result = BigDecimal.ZERO;
        for (var balance : balances) result = result.add(balance.remainingPresent());
        return money(result);
    }

    private void validateReferenceDate(ContractSummary contract, LocalDate referenceDate) {
        if (referenceDate.isBefore(contract.disbursementDate()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATE_BEFORE_DISBURSEMENT", "Reference date cannot precede contract disbursement");
    }

    private record Command(UUID contractId, LocalDate effectiveDate, BigDecimal amount, BigDecimal discount,
                           BigDecimal addition, String paymentMethod, String accountingReference) {}
}
