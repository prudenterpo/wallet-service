package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ReversalResponse(
        UUID reversalId,
        UUID settlementId,
        UUID contractId,
        LocalDate effectiveDate,
        BigDecimal reversedAmount,
        BigDecimal remainingBalance) {}
