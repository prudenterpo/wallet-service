package io.prudent.wallet.lending;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LendingModels {
    private LendingModels() {}

    public record BorrowerRequest(@NotBlank @Size(max = 100) String externalReference,
                                  @NotBlank @Size(max = 160) String displayName) {}
    public record BorrowerResponse(UUID id, String externalReference, String displayName) {}
    public record InstallmentInput(@Positive int number, @NotNull LocalDate dueDate,
                                   @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {}
    public record SimulationRequest(@NotNull LocalDate disbursementDate,
                                    @NotNull @DecimalMin("0.00000000") @DecimalMax("9999.99999999") @Digits(integer = 4, fraction = 8) BigDecimal annualRate,
                                    @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal fee,
                                    @NotEmpty List<@Valid InstallmentInput> installments) {}
    public record ScheduleItem(int number, LocalDate dueDate, int days,
                               BigDecimal presentValue, BigDecimal futureValue, BigDecimal interest) {}
    public record SimulationResponse(String ruleVersion, BigDecimal annualRate, BigDecimal fee,
                                     BigDecimal totalPresentValue, BigDecimal totalFutureValue,
                                     BigDecimal totalInterest, BigDecimal netAmount, List<ScheduleItem> schedule) {}
    public record ContractRequest(@NotNull UUID borrowerId, @NotBlank @Size(max = 100) String externalReference,
                                  @Valid @NotNull SimulationRequest terms) {}
    public record ContractResponse(UUID id, String externalReference, String status,
                                   BigDecimal originalPrincipal, String ruleVersion, List<ScheduleItem> schedule) {}
}
