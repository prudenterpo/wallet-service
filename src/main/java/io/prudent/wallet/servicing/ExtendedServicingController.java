package io.prudent.wallet.servicing;

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
public final class ExtendedServicingController {
    private final ServicingService servicingService;

    @PostMapping("/contracts/{contractId}/amortizations/{settlementId}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    ReversalResponse reverse(
            @PathVariable UUID contractId,
            @PathVariable UUID settlementId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReversalRequest request) {
        return servicingService.reverse(contractId, settlementId, idempotencyKey, request);
    }

    @PostMapping("/contracts/{contractId}/payoffs")
    @ResponseStatus(HttpStatus.CREATED)
    PayoffResponse payoff(
            @PathVariable UUID contractId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayoffRequest request) {
        return servicingService.payoff(contractId, idempotencyKey, request);
    }

    @GetMapping("/contracts/{contractId}/history")
    ContractHistoryResponse history(@PathVariable UUID contractId) {
        return servicingService.history(contractId);
    }

    @GetMapping("/portfolio/reconciliation")
    ReconciliationResponse reconciliation(@RequestParam LocalDate asOf) {
        return servicingService.reconciliation(asOf);
    }
}
