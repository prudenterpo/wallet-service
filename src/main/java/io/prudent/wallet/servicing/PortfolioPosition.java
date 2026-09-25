package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PortfolioPosition(
        LocalDate asOf,
        int contractCount,
        BigDecimal originalPrincipal,
        BigDecimal scheduled,
        BigDecimal paid,
        BigDecimal outstanding) {}
