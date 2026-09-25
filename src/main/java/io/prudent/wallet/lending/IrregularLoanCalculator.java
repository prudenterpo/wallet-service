package io.prudent.wallet.lending;

import io.prudent.wallet.platform.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class IrregularLoanCalculator {
    public static final String RULE_VERSION = "POC-SIMPLE-ACT-365-V1";
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final BigDecimal MAX_STORED_MONEY = new BigDecimal("99999999999999999.99");
    private static final int WORKING_SCALE = 12;

    public SimulationResponse calculate(SimulationRequest request) {
        var installmentNumbers = new HashSet<Integer>();
        var installments = new ArrayList<>(request.installments());
        installments.sort((left, right) -> Integer.compare(left.number(), right.number()));

        var schedule = new ArrayList<ScheduleItem>(installments.size());
        BigDecimal totalPresent = BigDecimal.ZERO;
        BigDecimal totalFuture = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        for (InstallmentInput input : installments) {
            validateInstallment(input, request, installmentNumbers);

            // TODO: Validate the ACT/365 simple-interest present-value formula against approved financial rules.
            int days = Math.toIntExact(ChronoUnit.DAYS.between(request.disbursementDate(), input.dueDate()));
            BigDecimal factor = BigDecimal.ONE.add(request.annualRate()
                    .multiply(BigDecimal.valueOf(days)).divide(DAYS_IN_YEAR, WORKING_SCALE, RoundingMode.HALF_EVEN));
            BigDecimal future = money(input.amount());
            BigDecimal present = money(future.divide(factor, WORKING_SCALE, RoundingMode.HALF_EVEN));
            BigDecimal interest = money(future.subtract(present));
            schedule.add(new ScheduleItem(input.number(), input.dueDate(), days, present, future, interest));
            totalPresent = totalPresent.add(present);
            totalFuture = totalFuture.add(future);
            totalInterest = totalInterest.add(interest);
        }
        BigDecimal present = money(totalPresent);
        BigDecimal future = money(totalFuture);
        BigDecimal interest = money(totalInterest);
        // TODO: Validate aggregation, fee treatment, and monetary rounding against approved financial rules.
        if (present.signum() <= 0 || present.compareTo(MAX_STORED_MONEY) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PRINCIPAL_OUT_OF_RANGE", "Calculated principal cannot be stored by this rule version");
        }
        if (request.fee().compareTo(present) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FEE_EXCEEDS_PRINCIPAL", "Fee cannot exceed calculated principal");
        }
        return new SimulationResponse(
                RULE_VERSION,
                request.annualRate().setScale(8, RoundingMode.HALF_EVEN),
                money(request.fee()),
                present,
                future,
                interest,
                money(present.subtract(request.fee())),
                schedule);
    }

    private void validateInstallment(
            InstallmentInput installment,
            SimulationRequest request,
            HashSet<Integer> installmentNumbers) {
        boolean hasUniqueNumber = installmentNumbers.add(installment.number());
        boolean isAfterDisbursement = installment.dueDate().isAfter(request.disbursementDate());
        if (!hasUniqueNumber || !isAfterDisbursement) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_SCHEDULE",
                    "Installment numbers must be unique and due dates must follow disbursement");
        }
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }
}
