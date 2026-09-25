package io.prudent.wallet.servicing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record PayoffRequest(
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 40) String paymentMethod,
        @NotBlank @Size(max = 100) String accountingReference) {}
