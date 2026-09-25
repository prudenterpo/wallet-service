package io.prudent.wallet.lending;

import static io.prudent.wallet.lending.LendingModels.*;

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
        var numbers = new HashSet<Integer>();
        var inputs = new ArrayList<>(request.installments());
        for (int index = 1; index < inputs.size(); index++) {
            var current = inputs.get(index);
            int position = index;
            while (position > 0 && inputs.get(position - 1).number() > current.number()) {
                inputs.set(position, inputs.get(position - 1));
                position--;
            }
            inputs.set(position, current);
        }
        var schedule = new ArrayList<ScheduleItem>(inputs.size());
        BigDecimal totalPresent = BigDecimal.ZERO;
        BigDecimal totalFuture = BigDecimal.ZERO;
        BigDecimal totalInterest = BigDecimal.ZERO;
        for (var input : inputs) {
            if (!numbers.add(input.number()) || !input.dueDate().isAfter(request.disbursementDate())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE", "Installment numbers must be unique and due dates must follow disbursement");
            }
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
        if (present.signum() <= 0 || present.compareTo(MAX_STORED_MONEY) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PRINCIPAL_OUT_OF_RANGE", "Calculated principal cannot be stored by this rule version");
        }
        if (request.fee().compareTo(present) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "FEE_EXCEEDS_PRINCIPAL", "Fee cannot exceed calculated principal");
        }
        return new SimulationResponse(RULE_VERSION, request.annualRate().setScale(8, RoundingMode.HALF_EVEN),
                money(request.fee()), present, future, interest, money(present.subtract(request.fee())), schedule);
    }

    public static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_EVEN); }

    public static BigDecimal remainingPresentValue(BigDecimal originalPresent, BigDecimal originalFuture, BigDecimal outstandingFuture) {
        if (outstandingFuture.signum() <= 0 || originalFuture.signum() <= 0) {
            return money(BigDecimal.ZERO);
        }
        if (outstandingFuture.compareTo(originalFuture) >= 0) {
            return money(originalPresent);
        }
        return money(originalPresent.multiply(outstandingFuture).divide(originalFuture, WORKING_SCALE, RoundingMode.HALF_EVEN));
    }

    public static BigDecimal remainingInterest(BigDecimal outstandingFuture, BigDecimal remainingPresent) {
        return money(outstandingFuture.subtract(remainingPresent));
    }
}
