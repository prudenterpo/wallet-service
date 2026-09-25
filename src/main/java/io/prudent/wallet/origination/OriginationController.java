package io.prudent.wallet.origination;

import io.prudent.wallet.lending.ContractResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/proposals")
@RequiredArgsConstructor
public final class OriginationController {
    private final OriginationService origination;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProposalResponse create(@RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ProposalRequest request) {
        return origination.createProposal(key, request);
    }

    @PostMapping("/{proposalId}/activation")
    @ResponseStatus(HttpStatus.CREATED)
    ContractResponse activate(@PathVariable UUID proposalId, @RequestHeader("Idempotency-Key") String key,
                              @Valid @RequestBody ActivationRequest request) {
        return origination.activate(proposalId, key, request);
    }
}
