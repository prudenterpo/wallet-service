package io.prudent.wallet.servicing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ReversalRequest(
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 200) String reason) {}
