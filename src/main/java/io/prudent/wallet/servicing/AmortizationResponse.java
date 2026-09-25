package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AmortizationResponse(
        UUID settlementId,
        UUID contractId,
        LocalDate effectiveDate,
        BigDecimal submittedAmount,
        BigDecimal allocatedAmount,
        BigDecimal remainingBalance,
        List<Allocation> allocations) {}
