package io.prudent.wallet.lending;

import java.math.BigDecimal;
import java.util.List;

public record SimulationResponse(
        String ruleVersion,
        BigDecimal annualRate,
        BigDecimal fee,
        BigDecimal totalPresentValue,
        BigDecimal totalFutureValue,
        BigDecimal totalInterest,
        BigDecimal netAmount,
        List<ScheduleItem> schedule) {}
