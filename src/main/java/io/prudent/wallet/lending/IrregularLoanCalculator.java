package io.prudent.wallet.lending;

import static io.prudent.wallet.lending.LendingModels.*;

import io.prudent.wallet.platform.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class IrregularLoanCalculator {
    public static final String RULE_VERSION = "POC-SIMPLE-ACT-365-V1";
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");
    private static final int WORKING_SCALE = 12;

    public SimulationResponse calculate(SimulationRequest request) {
        var numbers = new HashSet<Integer>();
        var schedule = request.installments().stream().sorted(java.util.Comparator.comparingInt(InstallmentInput::number)).map(input -> {
            if (!numbers.add(input.number()) || !input.dueDate().isAfter(request.disbursementDate())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SCHEDULE", "Installment numbers must be unique and due dates must follow disbursement");
            }
            int days = Math.toIntExact(ChronoUnit.DAYS.between(request.disbursementDate(), input.dueDate()));
            BigDecimal factor = BigDecimal.ONE.add(request.annualRate()
                    .multiply(BigDecimal.valueOf(days)).divide(DAYS_IN_YEAR, WORKING_SCALE, RoundingMode.HALF_EVEN));
            BigDecimal future = money(input.amount());
            BigDecimal present = money(future.divide(factor, WORKING_SCALE, RoundingMode.HALF_EVEN));
            return new ScheduleItem(input.number(), input.dueDate(), days, present, future, money(future.subtract(present)));
        }).toList();
        BigDecimal present = sum(schedule.stream().map(ScheduleItem::presentValue).toList());
        BigDecimal future = sum(schedule.stream().map(ScheduleItem::futureValue).toList());
        BigDecimal interest = sum(schedule.stream().map(ScheduleItem::interest).toList());
        return new SimulationResponse(RULE_VERSION, request.annualRate().setScale(8, RoundingMode.HALF_EVEN),
                money(request.fee()), present, future, interest, money(present.subtract(request.fee())), schedule);
    }

    private BigDecimal sum(java.util.List<BigDecimal> values) {
        return money(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_EVEN); }
}
