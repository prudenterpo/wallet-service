package io.prudent.wallet.origination;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProposalJourneyTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wallet.bootstrap.enabled", () -> "true");
        registry.add("wallet.bootstrap.organizations[0].name", () -> "Originator");
        registry.add("wallet.bootstrap.organizations[0].api-key", () -> "originator-key");
        registry.add("wallet.bootstrap.organizations[1].name", () -> "Other");
        registry.add("wallet.bootstrap.organizations[1].api-key", () -> "other-originator-key");
    }

    @LocalServerPort int port;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ProposalJourneyTest(ObjectMapper json) {
        this.json = json;
    }

    @Test
    void createsAndActivatesOneProposalExactlyOnce() throws Exception {
        JsonNode borrower = send("POST", "/api/v1/borrowers", "originator-key", null,
                "{\"externalReference\":\"proposal-borrower\",\"displayName\":\"Proposal Borrower\"}", 201);
        String terms = "\"disbursementDate\":\"2026-02-01\",\"annualRate\":0.10000000,\"fee\":2.00,\"installments\":[{\"number\":1,\"dueDate\":\"2027-02-01\",\"amount\":110.00}]";
        String proposalBody = "{\"borrowerId\":\"" + borrower.get("id").stringValue()
                + "\",\"externalReference\":\"proposal-1\",\"terms\":{" + terms + "}}";

        JsonNode proposal = send("POST", "/api/v1/proposals", "originator-key", "proposal-key", proposalBody, 201);
        assertThat(proposal.get("status").stringValue()).isEqualTo("PROPOSED");
        assertThat(proposal.get("originalPrincipal").decimalValue()).isEqualByComparingTo("100.00");
        String equivalentProposalBody = proposalBody
                .replace("0.10000000", "0.1")
                .replace("2.00", "2.0")
                .replace("110.00", "110.0");
        JsonNode proposalReplay = send(
                "POST", "/api/v1/proposals", "originator-key", "proposal-key", equivalentProposalBody, 201);
        assertThat(proposalReplay.get("id").stringValue()).isEqualTo(proposal.get("id").stringValue());

        String path = "/api/v1/proposals/" + proposal.get("id").stringValue() + "/activation";
        String activation = "{\"contractExternalReference\":\"proposal-contract-1\"}";
        var first = CompletableFuture.supplyAsync(() -> uncheckedSend(path, "activation-key", activation, 201));
        var second = CompletableFuture.supplyAsync(() -> uncheckedSend(path, "activation-key", activation, 201));
        JsonNode firstContract = first.join();
        JsonNode secondContract = second.join();
        assertThat(secondContract.get("id").stringValue()).isEqualTo(firstContract.get("id").stringValue());
        assertThat(firstContract.get("schedule").get(0).get("days").intValue()).isEqualTo(365);

        send("POST", path, "originator-key", "different-activation-key", activation, 409);
        send("POST", path, "other-originator-key", "other-activation-key", activation, 404);
        JsonNode position = send("GET", "/api/v1/contracts/" + firstContract.get("id").stringValue()
                + "/position?asOf=2026-02-01", "originator-key", null, null, 200);
        assertThat(position.get("outstanding").decimalValue()).isEqualByComparingTo("110.00");
    }

    private JsonNode uncheckedSend(String path, String key, String body, int expected) {
        try { return send("POST", path, "originator-key", key, body, expected); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private JsonNode send(String method, String path, String organizationKey, String idempotencyKey,
                          String body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Organization-Key", organizationKey).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expected);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
}
