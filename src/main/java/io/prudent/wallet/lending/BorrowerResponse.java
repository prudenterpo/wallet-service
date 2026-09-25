package io.prudent.wallet.lending;

import java.util.UUID;

public record BorrowerResponse(UUID id, String externalReference, String displayName) {
}
