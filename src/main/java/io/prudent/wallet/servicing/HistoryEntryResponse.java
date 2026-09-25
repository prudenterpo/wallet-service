package io.prudent.wallet.servicing;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record HistoryEntryResponse(
        UUID id,
        String action,
        UUID entityId,
        LocalDate effectiveDate,
        OffsetDateTime processedAt) {}
