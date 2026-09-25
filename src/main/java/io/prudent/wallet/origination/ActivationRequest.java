package io.prudent.wallet.origination;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActivationRequest(@NotBlank @Size(max = 100) String contractExternalReference) {}
