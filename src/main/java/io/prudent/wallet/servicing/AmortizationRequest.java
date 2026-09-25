package io.prudent.wallet.servicing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AmortizationRequest(
        @NotNull LocalDate effectiveDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal discount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal addition,
        @NotBlank @Size(max = 40) String paymentMethod,
        @NotBlank @Size(max = 100) String accountingReference) {}
