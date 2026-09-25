package io.prudent.wallet.lending;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ContractResponse(
        UUID id,
        String externalReference,
        String status,
        BigDecimal originalPrincipal,
        BigDecimal fee,
        BigDecimal netAmount,
        String ruleVersion,
        List<ScheduleItem> schedule) {}
