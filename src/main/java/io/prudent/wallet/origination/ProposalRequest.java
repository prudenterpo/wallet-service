package io.prudent.wallet.origination;

import io.prudent.wallet.lending.SimulationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ProposalRequest(
        @NotNull UUID borrowerId,
        @NotBlank @Size(max = 100) String externalReference,
        @Valid @NotNull SimulationRequest terms) {}
