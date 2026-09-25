package io.prudent.wallet.servicing;

import static io.prudent.wallet.lending.IrregularLoanCalculator.money;
import static io.prudent.wallet.lending.IrregularLoanCalculator.remainingInterest;
import static io.prudent.wallet.lending.IrregularLoanCalculator.remainingPresentValue;
import io.prudent.wallet.organization.OrganizationContext;
import io.prudent.wallet.platform.ApiException;
import io.prudent.wallet.platform.IdempotencyStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private record InstallmentBalance(
            UUID id,
            int number,
            LocalDate dueDate,
            BigDecimal present,
            BigDecimal scheduled,
            BigDecimal paid) {
        // TODO: Validate the nominal outstanding-balance rule against approved servicing rules.
        BigDecimal outstanding() { return money(scheduled.subtract(paid)); }
        BigDecimal remainingPresent() { return remainingPresentValue(present, scheduled, outstanding()); }
    }
    private record ContractSummary(
            UUID id,
            String reference,
            BigDecimal principal,
            LocalDate disbursementDate) {}
    private record PortfolioRow(
            UUID contractId,
            BigDecimal principal,
            BigDecimal present,
            BigDecimal scheduled,
            BigDecimal paid) {}
    private record SettlementSummary(
            UUID id,
            LocalDate effectiveDate,
            BigDecimal allocatedAmount,
            String type) {}

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
        validateEffectiveDateOrder(contract.id(), request.effectiveDate());
        // TODO: Validate addition, discount, and submitted-amount treatment against approved servicing rules.
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
        audit(organizationId, contract.id(), "AMORTIZATION_CREATED", settlementId, request.effectiveDate());
        idempotency.remember(organizationId, "AMORTIZATION", key, fingerprint, response);
        return response;
    }

    @Transactional
    public PayoffResponse payoff(UUID contractId, String key, PayoffRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, "PAYOFF", key);
        String fingerprint = idempotency.fingerprint(new PayoffCommand(
                contractId, request.effectiveDate(), request.paymentMethod(), request.accountingReference()));
        var replay = idempotency.replay(organizationId, "PAYOFF", key, fingerprint, PayoffResponse.class);
        if (replay != null) return replay;

        ContractSummary contract = lockContract(contractId, organizationId);
        validateReferenceDate(contract, request.effectiveDate());
        validateEffectiveDateOrder(contract.id(), request.effectiveDate());
        var balances = balances(contract.id(), request.effectiveDate());
        // TODO: Validate nominal payoff and future-interest treatment against approved payoff rules.
        BigDecimal outstanding = sumOutstanding(balances);
        if (outstanding.signum() == 0)
            throw new ApiException(HttpStatus.CONFLICT, "CONTRACT_ALREADY_SETTLED", "Contract has no nominal balance to settle");

        UUID settlementId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
        jdbc.sql("insert into settlement(id,organization_id,contract_id,effective_date,submitted_amount,discount,addition,allocated_amount,payment_method,accounting_reference,settlement_type,created_at) values (:id,:org,:contract,:date,:amount,0,0,:amount,:method,:reference,'PAYOFF',:now)")
                .param("id", settlementId).param("org", organizationId).param("contract", contract.id())
                .param("date", request.effectiveDate()).param("amount", outstanding)
                .param("method", request.paymentMethod()).param("reference", request.accountingReference())
                .param("now", now).update();
        var allocations = allocate(settlementId, contract.id(), outstanding, balances);
        if (!request.effectiveDate().isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC))))
            jdbc.sql("update loan_contract set status='SETTLED' where id=:id").param("id", contract.id()).update();
        audit(organizationId, contract.id(), "PAYOFF_CREATED", settlementId, request.effectiveDate());
        var response = new PayoffResponse(settlementId, contract.id(), request.effectiveDate(), outstanding,
                BigDecimal.ZERO.setScale(2), "NOMINAL_OPEN_BALANCE_WITHOUT_DISCOUNT", allocations);
        idempotency.remember(organizationId, "PAYOFF", key, fingerprint, response);
        return response;
    }

    @Transactional
    public ReversalResponse reverse(UUID contractId, UUID settlementId, String key, ReversalRequest request) {
        UUID organizationId = OrganizationContext.requiredId();
        idempotency.lock(organizationId, "REVERSAL", key);
        String fingerprint = idempotency.fingerprint(new ReversalCommand(
                contractId, settlementId, request.effectiveDate(), request.reason()));
        var replay = idempotency.replay(organizationId, "REVERSAL", key, fingerprint, ReversalResponse.class);
        if (replay != null) return replay;

        ContractSummary contract = lockContract(contractId, organizationId);
        validateReferenceDate(contract, request.effectiveDate());
        validateEffectiveDateOrder(contract.id(), request.effectiveDate());
        SettlementSummary latest = jdbc.sql("select s.id,s.effective_date,s.allocated_amount,s.settlement_type from settlement s left join settlement_reversal r on r.settlement_id=s.id where s.contract_id=:contract and r.id is null order by s.effective_date desc,s.created_at desc,s.id desc limit 1")
                .param("contract", contract.id()).query((row, ignored) -> new SettlementSummary(
                        row.getObject(1, UUID.class), row.getObject(2, LocalDate.class), row.getBigDecimal(3), row.getString(4)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND", "No reversible amortization was found"));
        if (!latest.id().equals(settlementId))
            throw new ApiException(HttpStatus.CONFLICT, "REVERSAL_REQUIRES_LATEST_SETTLEMENT", "Only the latest non-reversed amortization can be reversed");
        if (!"AMORTIZATION".equals(latest.type()))
            throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_NOT_REVERSIBLE", "Only amortization settlements can be reversed in this proof of concept");

        UUID reversalId = UUID.randomUUID();
        jdbc.sql("insert into settlement_reversal(id,organization_id,contract_id,settlement_id,effective_date,reason,created_at) values (:id,:org,:contract,:settlement,:date,:reason,:now)")
                .param("id", reversalId).param("org", organizationId).param("contract", contract.id())
                .param("settlement", settlementId).param("date", request.effectiveDate()).param("reason", request.reason())
                .param("now", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))).update();
        BigDecimal remainingBalance = sumOutstanding(balances(contract.id(), request.effectiveDate()));
        boolean settledNow = remainingBalance.signum() == 0
                && !request.effectiveDate().isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        jdbc.sql("update loan_contract set status=:status where id=:id")
                .param("status", settledNow ? "SETTLED" : "ACTIVE")
                .param("id", contract.id()).update();
        audit(organizationId, contract.id(), "AMORTIZATION_REVERSED", reversalId, request.effectiveDate());
        var response = new ReversalResponse(reversalId, settlementId, contract.id(), request.effectiveDate(),
                money(latest.allocatedAmount()), remainingBalance);
        idempotency.remember(organizationId, "REVERSAL", key, fingerprint, response);
        return response;
    }

    public ContractHistoryResponse history(UUID contractId) {
        UUID organizationId = OrganizationContext.requiredId();
        requireContract(contractId, organizationId);
        var entries = jdbc.sql("select id,action,entity_id,effective_date,processed_at from audit_entry where contract_id=:contract and organization_id=:org order by effective_date,processed_at,id")
                .param("contract", contractId).param("org", organizationId)
                .query((row, ignored) -> new HistoryEntryResponse(row.getObject(1, UUID.class), row.getString(2),
                        row.getObject(3, UUID.class), row.getObject(4, LocalDate.class), row.getObject(5, OffsetDateTime.class))).list();
        return new ContractHistoryResponse(contractId, entries);
    }

    public ReconciliationResponse reconciliation(LocalDate asOf) {
        UUID organizationId = OrganizationContext.requiredId();
        // TODO: Validate reconciliation totals and mismatch criteria against approved accounting rules.
        return jdbc.sql("with eligible_contracts as (select id from loan_contract where organization_id=:org and disbursement_date<=:asOf), installment_position as (select i.id,i.future_value scheduled,coalesce(sum(a.amount) filter (where s.effective_date<=:asOf and not exists (select 1 from settlement_reversal r where r.settlement_id=s.id and r.effective_date<=:asOf)),0) paid from installment i join eligible_contracts c on c.id=i.contract_id left join settlement_allocation a on a.installment_id=i.id left join settlement s on s.id=a.settlement_id group by i.id,i.future_value), allocation_mismatch as (select count(*) mismatches from settlement s join eligible_contracts c on c.id=s.contract_id where s.effective_date<=:asOf and s.allocated_amount<>(select coalesce(sum(a.amount),0) from settlement_allocation a where a.settlement_id=s.id)) select (select count(*) from eligible_contracts),coalesce(sum(scheduled),0),coalesce(sum(paid),0),count(*) filter (where paid>scheduled),(select mismatches from allocation_mismatch) from installment_position")
                .param("org", organizationId).param("asOf", asOf).query((row, ignored) -> {
                    BigDecimal scheduled = money(row.getBigDecimal(2));
                    BigDecimal paid = money(row.getBigDecimal(3));
                    int negativeBalances = row.getInt(4);
                    int allocationMismatches = row.getInt(5);
                    return new ReconciliationResponse(asOf, row.getInt(1), scheduled, paid,
                            money(scheduled.subtract(paid)), allocationMismatches, negativeBalances,
                            allocationMismatches == 0 && negativeBalances == 0);
                }).single();
    }

    public ContractPosition contractPosition(UUID contractId, LocalDate asOf) {
        UUID organizationId = OrganizationContext.requiredId();
        // TODO: Validate historical paid and outstanding calculations against approved portfolio rules.
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
        // TODO: Validate portfolio aggregation and reversal treatment against approved portfolio rules.
        var rows = jdbc.sql("""
                select c.id, c.original_principal, i.present_value, i.future_value,
                       coalesce((select sum(a.amount) from settlement_allocation a join settlement s on s.id = a.settlement_id
                                 where a.installment_id = i.id and s.effective_date <= :asOf
                                   and not exists (select 1 from settlement_reversal r where r.settlement_id = s.id and r.effective_date <= :asOf)), 0)
                from loan_contract c
                join installment i on i.contract_id = c.id
                where c.organization_id = :org and c.disbursement_date <= :asOf
                """)
                .param("asOf", asOf).param("org", organizationId)
                .query((row, ignored) -> new PortfolioRow(row.getObject(1, UUID.class), row.getBigDecimal(2),
                        row.getBigDecimal(3), row.getBigDecimal(4), row.getBigDecimal(5)))
                .list();
        var contractIds = new java.util.HashSet<UUID>();
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
            remainingPresent = remainingPresent.add(remainingPresentValue(
                    row.present(), row.scheduled(), money(row.scheduled().subtract(row.paid()))));
        }
        scheduled = money(scheduled);
        paid = money(paid);
        remainingPresent = money(remainingPresent);
        BigDecimal outstanding = money(scheduled.subtract(paid));
        return new PortfolioPosition(asOf, contractIds.size(), money(originalPrincipal), scheduled, paid, outstanding,
                remainingPresent, remainingInterest(outstanding, remainingPresent));
    }

    private java.util.List<InstallmentBalance> balances(UUID contractId, LocalDate asOf) {
        return jdbc.sql("select i.id,i.installment_number,i.due_date,i.present_value,i.future_value,coalesce(sum(a.amount) filter (where s.effective_date<=:asOf and not exists (select 1 from settlement_reversal r where r.settlement_id=s.id and r.effective_date<=:asOf)),0) from installment i left join settlement_allocation a on a.installment_id=i.id left join settlement s on s.id=a.settlement_id where i.contract_id=:contract group by i.id,i.installment_number,i.due_date,i.present_value,i.future_value order by i.due_date,i.installment_number")
                .param("asOf", asOf).param("contract", contractId).query((row, ignored) ->
                        new InstallmentBalance(row.getObject(1, UUID.class), row.getInt(2), row.getObject(3, LocalDate.class),
                                row.getBigDecimal(4), row.getBigDecimal(5), row.getBigDecimal(6))).list();
    }

    private BigDecimal totalOutstanding(UUID contractId) {
        // TODO: Validate current outstanding aggregation and reversal treatment against approved servicing rules.
        return money(jdbc.sql("select coalesce(sum(i.future_value),0)-coalesce((select sum(a.amount) from settlement_allocation a join settlement s on s.id=a.settlement_id join installment x on x.id=a.installment_id where x.contract_id=:contract and not exists (select 1 from settlement_reversal r where r.settlement_id=s.id)),0) from installment i where i.contract_id=:contract")
                .param("contract", contractId).query(BigDecimal.class).single());
    }

    private ContractSummary lockContract(UUID contractId, UUID organizationId) {
        return jdbc.sql("select id,external_reference,original_principal,disbursement_date from loan_contract where id=:id and organization_id=:org for update")
                .param("id", contractId).param("org", organizationId).query((row, ignored) ->
                        new ContractSummary(row.getObject(1, UUID.class), row.getString(2), row.getBigDecimal(3),
                                row.getObject(4, LocalDate.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND",
                        "Contract was not found in this organization"));
    }

    private void requireContract(UUID contractId, UUID organizationId) {
        boolean exists = jdbc.sql("select count(*) from loan_contract where id=:id and organization_id=:org")
                .param("id", contractId).param("org", organizationId).query(Integer.class).single() == 1;
        if (!exists)
            throw new ApiException(HttpStatus.NOT_FOUND, "CONTRACT_NOT_FOUND", "Contract was not found in this organization");
    }

    private void validateEffectiveDateOrder(UUID contractId, LocalDate effectiveDate) {
        @Nullable LocalDate latestEffectiveDate = jdbc.sql("select max(effective_date) from (select effective_date from settlement where contract_id=:contract union all select effective_date from settlement_reversal where contract_id=:contract) financial_event")
                .param("contract", contractId).query(LocalDate.class).optional().orElse(null);
        if (latestEffectiveDate != null && effectiveDate.isBefore(latestEffectiveDate))
            throw new ApiException(HttpStatus.CONFLICT, "OUT_OF_ORDER_EFFECTIVE_DATE",
                    "Settlement commands must be submitted in nondecreasing effective-date order");
    }

    private java.util.List<Allocation> allocate(UUID settlementId, UUID contractId, BigDecimal amount,
                                                 java.util.List<InstallmentBalance> balances) {
        // TODO: Validate FIFO allocation and installment-order rules against approved servicing rules.
        BigDecimal remaining = amount;
        var allocations = new ArrayList<Allocation>();
        for (var balance : balances) {
            if (remaining.signum() == 0) break;
            BigDecimal allocation = remaining.min(balance.outstanding());
            if (allocation.signum() > 0) {
                jdbc.sql("insert into settlement_allocation(settlement_id,installment_id,contract_id,amount) values (:settlement,:installment,:contract,:amount)")
                        .param("settlement", settlementId).param("installment", balance.id()).param("contract", contractId)
                        .param("amount", allocation).update();
                allocations.add(new Allocation(balance.id(), balance.number(), allocation));
                remaining = money(remaining.subtract(allocation));
            }
        }
        return allocations;
    }

    private void audit(UUID organizationId, UUID contractId, String action, UUID entityId, LocalDate effectiveDate) {
        jdbc.sql("insert into audit_entry(id,organization_id,contract_id,action,entity_id,effective_date,processed_at,metadata) values (:id,:org,:contract,:action,:entity,:date,:now,'{}'::jsonb)")
                .param("id", UUID.randomUUID()).param("org", organizationId).param("contract", contractId)
                .param("action", action).param("entity", entityId).param("date", effectiveDate)
                .param("now", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))).update();
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

    private record Command(
            UUID contractId,
            LocalDate effectiveDate,
            BigDecimal amount,
            BigDecimal discount,
            BigDecimal addition,
            String paymentMethod,
            String accountingReference) {}
    private record PayoffCommand(
            UUID contractId,
            LocalDate effectiveDate,
            String paymentMethod,
            String accountingReference) {}
    private record ReversalCommand(
            UUID contractId,
            UUID settlementId,
            LocalDate effectiveDate,
            String reason) {}
}
