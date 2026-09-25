package io.prudent.wallet.servicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContractPosition(
        UUID contractId,
        String externalReference,
        LocalDate asOf,
        String status,
        BigDecimal originalPrincipal,
        BigDecimal scheduled,
        BigDecimal paid,
        BigDecimal outstanding,
        List<InstallmentPosition> installments) {}
