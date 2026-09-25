package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.util.UUID;

public record Allocation(
        UUID installmentId,
        int installmentNumber,
        BigDecimal amount) {}
