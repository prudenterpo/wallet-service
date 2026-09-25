package io.prudent.wallet.lending;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ScheduleItem(
        int number,
        LocalDate dueDate,
        int days,
        BigDecimal presentValue,
        BigDecimal futureValue,
        BigDecimal interest) {}
