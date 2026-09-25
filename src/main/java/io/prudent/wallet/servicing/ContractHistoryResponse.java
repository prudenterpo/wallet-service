package io.prudent.wallet.servicing;

import java.util.List;
import java.util.UUID;

public record ContractHistoryResponse(UUID contractId, List<HistoryEntryResponse> entries) {}
