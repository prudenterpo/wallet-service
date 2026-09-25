package io.prudent.wallet.servicing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ServicingModels {
    private ServicingModels() {}

    public record AmortizationRequest(@NotNull LocalDate effectiveDate,
                                      @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                      @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal discount,
                                      @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal addition,
                                      @NotBlank @Size(max = 40) String paymentMethod,
                                      @NotBlank @Size(max = 100) String accountingReference) {}
    public record Allocation(UUID installmentId, int installmentNumber, BigDecimal amount) {}
    public record AmortizationResponse(UUID settlementId, UUID contractId, LocalDate effectiveDate,
                                       BigDecimal submittedAmount, BigDecimal allocatedAmount,
                                       BigDecimal remainingBalance, List<Allocation> allocations) {}
    public record InstallmentPosition(UUID installmentId, int number, LocalDate dueDate,
                                      BigDecimal scheduled, BigDecimal paid, BigDecimal outstanding) {}
    public record ContractPosition(UUID contractId, String externalReference, LocalDate asOf, String status,
                                   BigDecimal originalPrincipal, BigDecimal scheduled,
                                   BigDecimal paid, BigDecimal outstanding, List<InstallmentPosition> installments) {}
    public record PortfolioPosition(LocalDate asOf, int contractCount, BigDecimal originalPrincipal,
                                    BigDecimal scheduled, BigDecimal paid, BigDecimal outstanding) {}
}
