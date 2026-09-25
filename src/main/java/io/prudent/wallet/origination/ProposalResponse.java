package io.prudent.wallet.origination;

import io.prudent.wallet.lending.ScheduleItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ProposalResponse(
        UUID id,
        UUID borrowerId,
        String externalReference,
        String status,
        String ruleVersion,
        LocalDate disbursementDate,
        BigDecimal originalPrincipal,
        BigDecimal annualRate,
        BigDecimal fee,
        List<ScheduleItem> schedule) {}
