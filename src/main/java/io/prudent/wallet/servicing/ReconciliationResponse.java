package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReconciliationResponse(
        LocalDate asOf,
        int contractCount,
        BigDecimal scheduled,
        BigDecimal paid,
        BigDecimal outstanding,
        int allocationMismatchCount,
        int negativeBalanceCount,
        boolean reconciled) {}
