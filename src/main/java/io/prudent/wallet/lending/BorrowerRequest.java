package io.prudent.wallet.lending;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BorrowerRequest(
        @NotBlank @Size(max = 100) String externalReference,
        @NotBlank @Size(max = 160) String displayName) {}
