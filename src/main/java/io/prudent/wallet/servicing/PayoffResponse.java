package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayoffResponse(
        UUID settlementId,
        UUID contractId,
        LocalDate effectiveDate,
        BigDecimal nominalAmount,
        BigDecimal remainingBalance,
        String rule,
        List<Allocation> allocations) {}
