package io.prudent.wallet.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prudent.wallet.lending.LendingModels.InstallmentInput;
import io.prudent.wallet.lending.LendingModels.SimulationRequest;
import io.prudent.wallet.platform.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IrregularLoanCalculatorTest {
    private final IrregularLoanCalculator calculator = new IrregularLoanCalculator();

    @Test
    void calculatesTheDocumentedAct365ReferenceScenario() {
        var result = calculator.calculate(new SimulationRequest(
                LocalDate.of(2026, 1, 1), new BigDecimal("0.10000000"), new BigDecimal("2.00"),
                List.of(new InstallmentInput(1, LocalDate.of(2027, 1, 1), new BigDecimal("110.00")))));

        assertThat(result.ruleVersion()).isEqualTo("POC-SIMPLE-ACT-365-V1");
        assertThat(result.totalPresentValue()).isEqualByComparingTo("100.00");
        assertThat(result.totalFutureValue()).isEqualByComparingTo("110.00");
        assertThat(result.totalInterest()).isEqualByComparingTo("10.00");
        assertThat(result.netAmount()).isEqualByComparingTo("98.00");
    }

    @Test
    void rejectsTermsThatCannotProduceAPersistableContract() {
        var excessiveFee = new SimulationRequest(
                LocalDate.of(2026, 1, 1), BigDecimal.ZERO, new BigDecimal("101.00"),
                List.of(new InstallmentInput(1, LocalDate.of(2027, 1, 1), new BigDecimal("100.00"))));
        assertThatThrownBy(() -> calculator.calculate(excessiveFee))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FEE_EXCEEDS_PRINCIPAL"));

        var roundedToZero = new SimulationRequest(
                LocalDate.of(2026, 1, 1), new BigDecimal("9999.99999999"), BigDecimal.ZERO,
                List.of(new InstallmentInput(1, LocalDate.of(2027, 1, 1), new BigDecimal("0.01"))));
        assertThatThrownBy(() -> calculator.calculate(roundedToZero))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("PRINCIPAL_OUT_OF_RANGE"));
    }
}
