package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InstallmentPosition(
        UUID installmentId,
        int number,
        LocalDate dueDate,
        BigDecimal scheduled,
        BigDecimal paid,
        BigDecimal outstanding) {}
