package io.prudent.wallet.lending;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SimulationRequest(
        @NotNull LocalDate disbursementDate,
        @NotNull
        @DecimalMin("0.00000000")
        @DecimalMax("9999.99999999")
        @Digits(integer = 4, fraction = 8)
        BigDecimal annualRate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal fee,
        @NotEmpty List<@Valid InstallmentInput> installments) {}
