package io.prudent.wallet;

import io.prudent.wallet.lending.BorrowerRequest;
import io.prudent.wallet.lending.BorrowerResponse;
import io.prudent.wallet.lending.ContractRequest;
import io.prudent.wallet.lending.ContractResponse;
import io.prudent.wallet.lending.LendingService;
import io.prudent.wallet.lending.SimulationRequest;
import io.prudent.wallet.lending.SimulationResponse;
import io.prudent.wallet.servicing.AmortizationRequest;
import io.prudent.wallet.servicing.AmortizationResponse;
import io.prudent.wallet.servicing.ContractPosition;
import io.prudent.wallet.servicing.PortfolioPosition;
import io.prudent.wallet.servicing.ServicingService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public final class WalletController {
    private final LendingService lending;
    private final ServicingService servicing;

    @PostMapping("/borrowers")
    @ResponseStatus(HttpStatus.CREATED)
    BorrowerResponse createBorrower(@Valid @RequestBody BorrowerRequest request) {
        return lending.createBorrower(request);
    }

    @PostMapping("/simulations")
    SimulationResponse simulate(@Valid @RequestBody SimulationRequest request) {
        return lending.simulate(request);
    }

    @PostMapping("/contracts")
    @ResponseStatus(HttpStatus.CREATED)
    ContractResponse createContract(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ContractRequest request) {
        return lending.createContract(key, request);
    }

    @GetMapping("/contracts/{contractId}/position")
    ContractPosition position(@PathVariable UUID contractId, @RequestParam LocalDate asOf) {
        return servicing.contractPosition(contractId, asOf);
    }

    @PostMapping("/contracts/{contractId}/amortizations")
    @ResponseStatus(HttpStatus.CREATED)
    AmortizationResponse amortize(@PathVariable UUID contractId, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody AmortizationRequest request) {
        return servicing.amortize(contractId, key, request);
    }

    @GetMapping("/portfolio/position")
    PortfolioPosition portfolio(@RequestParam LocalDate asOf) {
        return servicing.portfolioPosition(asOf);
    }
}
