package io.prudent.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletJourneyTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("wallet.bootstrap.enabled", () -> "true");
        registry.add("wallet.bootstrap.organizations[0].name", () -> "Primary");
        registry.add("wallet.bootstrap.organizations[0].api-key", () -> "primary-key");
        registry.add("wallet.bootstrap.organizations[1].name", () -> "Other");
        registry.add("wallet.bootstrap.organizations[1].api-key", () -> "other-key");
    }

    @LocalServerPort int port;
    private final ObjectMapper json;
    private final JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    WalletJourneyTest(ObjectMapper json, JdbcClient jdbc) {
        this.json = json;
        this.jdbc = jdbc;
    }

    @Test
    @DisplayName("Given an isolated organization, when the complete wallet journey is replayed concurrently, then balances change exactly once")
    void completesTheHttpJourneyAndSerializesConcurrentIdempotentAmortization() throws Exception {
        String borrowerBody = "{\"externalReference\":\"borrower-1\",\"displayName\":\"Synthetic Borrower\"}";
        var borrowerFirst = CompletableFuture.supplyAsync(() -> uncheckedSend("POST", "/api/v1/borrowers", "primary-key", null, borrowerBody, 201));
        var borrowerSecond = CompletableFuture.supplyAsync(() -> uncheckedSend("POST", "/api/v1/borrowers", "primary-key", null, borrowerBody, 201));
        JsonNode borrower = borrowerFirst.join();
        assertThat(borrowerSecond.join().get("id").stringValue()).isEqualTo(borrower.get("id").stringValue());
        String borrowerId = borrower.get("id").stringValue();
        String terms = "\"disbursementDate\":\"2026-01-01\",\"annualRate\":0.00000000,\"fee\":0.00,\"installments\":[{\"number\":1,\"dueDate\":\"2026-06-01\",\"amount\":40.00},{\"number\":2,\"dueDate\":\"2026-12-01\",\"amount\":60.00}]";
        JsonNode simulation = send("POST", "/api/v1/simulations", "primary-key", null, "{" + terms + "}", 200);
        assertThat(simulation.get("totalPresentValue").decimalValue()).isEqualByComparingTo("100.00");

        String contractBody = "{\"borrowerId\":\"" + borrowerId + "\",\"externalReference\":\"contract-1\",\"terms\":{" + terms + "}}";
        JsonNode contract = send("POST", "/api/v1/contracts", "primary-key", "contract-key", contractBody, 201);
        String contractId = contract.get("id").stringValue();
        JsonNode replay = send("POST", "/api/v1/contracts", "primary-key", "contract-key", contractBody, 201);
        assertThat(replay.get("id").stringValue()).isEqualTo(contractId);
        JsonNode decimalReplay = send("POST", "/api/v1/contracts", "primary-key", "contract-key",
                contractBody.replace("40.00", "40.0").replace("60.00", "60.0"), 201);
        assertThat(decimalReplay.get("id").stringValue()).isEqualTo(contractId);
        send("POST", "/api/v1/contracts", "primary-key", "duplicate-contract-key", contractBody, 409);

        String amortization = "{\"effectiveDate\":\"2026-07-01\",\"amount\":30.00,\"discount\":0.00,\"addition\":0.00,\"paymentMethod\":\"TRANSFER\",\"accountingReference\":\"payment-1\"}";
        var first = CompletableFuture.supplyAsync(() -> uncheckedSend(contractId, amortization));
        var second = CompletableFuture.supplyAsync(() -> uncheckedSend(contractId, amortization));
        JsonNode a = first.join();
        JsonNode b = second.join();
        assertThat(a.get("settlementId").stringValue()).isEqualTo(b.get("settlementId").stringValue());
        JsonNode decimalAmortizationReplay = send("POST", "/api/v1/contracts/" + contractId + "/amortizations",
                "primary-key", "payment-key", amortization.replace("30.00", "30.0"), 201);
        assertThat(decimalAmortizationReplay.get("settlementId").stringValue()).isEqualTo(a.get("settlementId").stringValue());
        assertThat(jdbc.sql("select count(*) from settlement").query(Integer.class).single()).isEqualTo(1);
        String conflictingAmortization = amortization.replace("30.00", "31.00");
        send("POST", "/api/v1/contracts/" + contractId + "/amortizations", "primary-key", "payment-key", conflictingAmortization, 409);
        String backdatedAmortization = amortization.replace("2026-07-01", "2026-06-01");
        send("POST", "/api/v1/contracts/" + contractId + "/amortizations", "primary-key", "backdated-payment-key", backdatedAmortization, 409);

        JsonNode position = send("GET", "/api/v1/contracts/" + contractId + "/position?asOf=2026-07-01", "primary-key", null, null, 200);
        assertThat(position.get("paid").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(position.get("outstanding").decimalValue()).isEqualByComparingTo("70.00");
        assertThat(position.get("remainingPresentValue").decimalValue()).isEqualByComparingTo("70.00");
        assertThat(position.get("installments").get(0).get("paid").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(a.get("allocations").get(0).get("installmentNumber").asInt()).isEqualTo(1);

        String remaining = "{\"effectiveDate\":\"2999-01-01\",\"amount\":70.00,\"discount\":0.00,\"addition\":0.00,\"paymentMethod\":\"TRANSFER\",\"accountingReference\":\"payment-future\"}";
        send("POST", "/api/v1/contracts/" + contractId + "/amortizations", "primary-key", "future-settle-key", remaining, 201);
        assertThat(jdbc.sql("select status from loan_contract where id=:id").param("id", java.util.UUID.fromString(contractId)).query(String.class).single())
                .isEqualTo("ACTIVE");
        JsonNode stillOpen = send("GET", "/api/v1/contracts/" + contractId + "/position?asOf=2026-07-01", "primary-key", null, null, 200);
        assertThat(stillOpen.get("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(stillOpen.get("outstanding").decimalValue()).isEqualByComparingTo("70.00");
        JsonNode settledAsOf = send("GET", "/api/v1/contracts/" + contractId + "/position?asOf=2999-01-01", "primary-key", null, null, 200);
        assertThat(settledAsOf.get("status").stringValue()).isEqualTo("SETTLED");

        String interestTerms = "\"disbursementDate\":\"2026-01-01\",\"annualRate\":0.10000000,\"fee\":2.00,\"installments\":[{\"number\":1,\"dueDate\":\"2027-01-01\",\"amount\":110.00}]";
        JsonNode interestContract = send("POST", "/api/v1/contracts", "primary-key", "interest-contract-key",
                "{\"borrowerId\":\"" + borrowerId + "\",\"externalReference\":\"interest-1\",\"terms\":{" + interestTerms + "}}", 201);
        assertThat(interestContract.get("originalPrincipal").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(interestContract.get("fee").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(interestContract.get("netAmount").decimalValue()).isEqualByComparingTo("98.00");
        String interestPayment = "{\"effectiveDate\":\"2026-07-01\",\"amount\":10.00,\"discount\":0.00,\"addition\":0.00,\"paymentMethod\":\"TRANSFER\",\"accountingReference\":\"interest-pay\"}";
        JsonNode interestAmortization = send("POST", "/api/v1/contracts/" + interestContract.get("id").stringValue() + "/amortizations",
                "primary-key", "interest-pay-key", interestPayment, 201);
        assertThat(interestAmortization.get("allocations").get(0).get("installmentNumber").asInt()).isEqualTo(1);
        assertThat(interestAmortization.get("remainingBalance").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(interestAmortization.get("remainingPresentValue").decimalValue()).isEqualByComparingTo("90.91");
        JsonNode interestPosition = send("GET", "/api/v1/contracts/" + interestContract.get("id").stringValue() + "/position?asOf=2026-07-01",
                "primary-key", null, null, 200);
        assertThat(interestPosition.get("outstanding").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(interestPosition.get("remainingPresentValue").decimalValue()).isEqualByComparingTo("90.91");
        assertThat(interestPosition.get("remainingInterest").decimalValue()).isEqualByComparingTo("9.09");

        String futureTerms = "\"disbursementDate\":\"2027-01-01\",\"annualRate\":0.00000000,\"fee\":0.00,\"installments\":[{\"number\":1,\"dueDate\":\"2028-01-01\",\"amount\":50.00}]";
        String futureContract = "{\"borrowerId\":\"" + borrowerId + "\",\"externalReference\":\"future-contract\",\"terms\":{" + futureTerms + "}}";
        send("POST", "/api/v1/contracts", "primary-key", "future-contract-key", futureContract, 201);
        JsonNode portfolio = send("GET", "/api/v1/portfolio/position?asOf=2026-07-01", "primary-key", null, null, 200);
        assertThat(portfolio.get("contractCount").asInt()).isEqualTo(2);
        assertThat(portfolio.get("outstanding").decimalValue()).isEqualByComparingTo("170.00");
        assertThat(portfolio.get("remainingPresentValue").decimalValue()).isEqualByComparingTo("160.91");
        send("GET", "/api/v1/contracts/" + contractId + "/position?asOf=2026-07-01", "other-key", null, null, 404);
        JsonNode invalidDate = send("GET", "/api/v1/contracts/" + contractId + "/position?asOf=not-a-date",
                "primary-key", null, null, 400);
        assertThat(invalidDate.get("code").stringValue()).isEqualTo("INVALID_REQUEST");
    }

    private JsonNode uncheckedSend(String contractId, String body) {
        return uncheckedSend("POST", "/api/v1/contracts/" + contractId + "/amortizations", "primary-key", "payment-key", body, 201);
    }

    private JsonNode uncheckedSend(String method, String path, String organizationKey, String idempotencyKey, String body, int expected) {
        try { return send(method, path, organizationKey, idempotencyKey, body, expected); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }

    private JsonNode send(String method, String path, String organizationKey, String idempotencyKey, String body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Organization-Key", organizationKey).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expected);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
}
