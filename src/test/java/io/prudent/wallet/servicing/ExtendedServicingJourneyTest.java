package io.prudent.wallet.servicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExtendedServicingJourneyTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

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
    ExtendedServicingJourneyTest(ObjectMapper json, JdbcClient jdbc) {
        this.json = json;
        this.jdbc = jdbc;
    }

    @Test
    @DisplayName("Given an amortized contract, payoff and latest-only reversal remain idempotent and reconcilable")
    void settlesReversesAndExplainsTheContract() throws Exception {
        JsonNode borrower = send("POST", "/api/v1/borrowers", "primary-key", null,
                "{\"externalReference\":\"servicing-borrower\",\"displayName\":\"Servicing Borrower\"}", 201);
        String terms = "\"disbursementDate\":\"2026-01-01\",\"annualRate\":0.00000000,\"fee\":0.00,"
                + "\"installments\":[{\"number\":1,\"dueDate\":\"2026-06-01\",\"amount\":40.00},"
                + "{\"number\":2,\"dueDate\":\"2026-12-01\",\"amount\":60.00}]";
        String contractBody = "{\"borrowerId\":\"" + borrower.get("id").stringValue()
                + "\",\"externalReference\":\"servicing-contract\",\"terms\":{" + terms + "}}";
        JsonNode contract = send("POST", "/api/v1/contracts", "primary-key", "servicing-contract-key",
                contractBody, 201);
        String contractId = contract.get("id").stringValue();

        String amortization = "{\"effectiveDate\":\"2026-07-01\",\"amount\":30.00,\"discount\":0.00,"
                + "\"addition\":0.00,\"paymentMethod\":\"TRANSFER\",\"accountingReference\":\"payment-1\"}";
        JsonNode firstPayment = send("POST", "/api/v1/contracts/" + contractId + "/amortizations",
                "primary-key", "servicing-payment-key", amortization, 201);
        String payoffBody = "{\"effectiveDate\":\"2026-08-01\",\"paymentMethod\":\"TRANSFER\","
                + "\"accountingReference\":\"payoff-1\"}";
        var payoffOne = CompletableFuture.supplyAsync(() -> uncheckedSend("POST",
                "/api/v1/contracts/" + contractId + "/payoffs", "primary-key", "payoff-key", payoffBody, 201));
        var payoffTwo = CompletableFuture.supplyAsync(() -> uncheckedSend("POST",
                "/api/v1/contracts/" + contractId + "/payoffs", "primary-key", "payoff-key", payoffBody, 201));
        JsonNode payoff = payoffOne.join();
        assertThat(payoffTwo.join().get("settlementId").stringValue())
                .isEqualTo(payoff.get("settlementId").stringValue());
        assertThat(payoff.get("nominalAmount").decimalValue()).isEqualByComparingTo("70.00");
        assertThat(payoff.get("rule").stringValue()).isEqualTo("NOMINAL_OPEN_BALANCE_WITHOUT_DISCOUNT");
        send("POST", "/api/v1/contracts/" + contractId + "/payoffs", "primary-key", "payoff-key",
                payoffBody.replace("payoff-1", "different-payoff"), 409);

        String reversalBody = "{\"effectiveDate\":\"2026-09-01\",\"reason\":\"POC correction\"}";
        send("POST", "/api/v1/contracts/" + contractId + "/amortizations/"
                + firstPayment.get("settlementId").stringValue() + "/reversal", "primary-key",
                "wrong-reversal-key", reversalBody, 409);
        String reversalPath = "/api/v1/contracts/" + contractId + "/amortizations/"
                + payoff.get("settlementId").stringValue() + "/reversal";
        send("POST", reversalPath, "primary-key", "reversal-key", reversalBody, 409);
        send("POST", reversalPath, "other-key", "other-reversal-key", reversalBody, 404);
        UUID organizationId = jdbc.sql("select organization_id from loan_contract where id=:contract")
                .param("contract", UUID.fromString(contractId)).query(UUID.class).single();
        assertThatThrownBy(() -> jdbc.sql("insert into settlement_reversal(id,organization_id,contract_id,settlement_id,effective_date,reason,created_at) values (:id,:org,:contract,:settlement,date '2026-07-31','invalid temporal order',:now)")
                .param("id", UUID.randomUUID()).param("org", organizationId)
                .param("contract", UUID.fromString(contractId))
                .param("settlement", UUID.fromString(payoff.get("settlementId").stringValue()))
                .param("now", OffsetDateTime.now()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        JsonNode position = send("GET", "/api/v1/contracts/" + contractId + "/position?asOf=2026-09-01",
                "primary-key", null, null, 200);
        assertThat(position.get("paid").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(position.get("outstanding").decimalValue()).isEqualByComparingTo("0.00");
        JsonNode history = send("GET", "/api/v1/contracts/" + contractId + "/history",
                "primary-key", null, null, 200);
        assertThat(history.get("entries").size()).isEqualTo(2);

        String reversibleContractBody = contractBody.replace("servicing-contract", "reversible-contract");
        JsonNode reversibleContract = send("POST", "/api/v1/contracts", "primary-key", "reversible-contract-key",
                reversibleContractBody, 201);
        String reversibleContractId = reversibleContract.get("id").stringValue();
        String firstAmortization = amortization.replace("30.00", "20.00");
        JsonNode first = send("POST", "/api/v1/contracts/" + reversibleContractId + "/amortizations",
                "primary-key", "first-reversible-key", firstAmortization, 201);
        String secondAmortization = amortization.replace("2026-07-01", "2026-08-01")
                .replace("30.00", "10.00").replace("payment-1", "payment-2");
        JsonNode second = send("POST", "/api/v1/contracts/" + reversibleContractId + "/amortizations",
                "primary-key", "second-reversible-key", secondAmortization, 201);

        String secondReversalPath = "/api/v1/contracts/" + reversibleContractId + "/amortizations/"
                + second.get("settlementId").stringValue() + "/reversal";
        JsonNode secondReversal = send("POST", secondReversalPath, "primary-key", "second-reversal-key",
                reversalBody, 201);
        JsonNode secondReversalReplay = send("POST", secondReversalPath, "primary-key", "second-reversal-key",
                reversalBody, 201);
        assertThat(secondReversalReplay.get("reversalId").stringValue())
                .isEqualTo(secondReversal.get("reversalId").stringValue());
        send("POST", secondReversalPath, "primary-key", "second-reversal-key",
                reversalBody.replace("POC correction", "Different correction"), 409);

        JsonNode beforeReversal = send("GET", "/api/v1/contracts/" + reversibleContractId
                + "/position?asOf=2026-08-31", "primary-key", null, null, 200);
        JsonNode atReversal = send("GET", "/api/v1/contracts/" + reversibleContractId
                + "/position?asOf=2026-09-01", "primary-key", null, null, 200);
        assertThat(beforeReversal.get("paid").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(atReversal.get("paid").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(secondReversal.get("remainingBalance").decimalValue()).isEqualByComparingTo("80.00");
        send("POST", "/api/v1/contracts/" + reversibleContractId + "/amortizations", "primary-key",
                "out-of-order-payment-key", secondAmortization.replace("2026-08-01", "2026-08-15"), 409);

        String firstReversalPath = "/api/v1/contracts/" + reversibleContractId + "/amortizations/"
                + first.get("settlementId").stringValue() + "/reversal";
        send("POST", firstReversalPath, "primary-key", "out-of-order-reversal-key",
                reversalBody.replace("2026-09-01", "2026-08-15"), 409);
        String finalReversal = reversalBody.replace("2026-09-01", "2026-10-01");
        JsonNode finalReversalResponse = send("POST", firstReversalPath, "primary-key", "final-reversal-key",
                finalReversal, 201);
        assertThat(finalReversalResponse.get("remainingBalance").decimalValue()).isEqualByComparingTo("100.00");
        JsonNode afterReversal = send("GET", "/api/v1/contracts/" + reversibleContractId
                + "/position?asOf=2026-10-01", "primary-key", null, null, 200);
        assertThat(afterReversal.get("paid").decimalValue()).isEqualByComparingTo("0.00");

        JsonNode reconciliation = send("GET", "/api/v1/portfolio/reconciliation?asOf=2026-10-01",
                "primary-key", null, null, 200);
        assertThat(reconciliation.get("reconciled").asBoolean()).isTrue();
        assertThat(reconciliation.get("paid").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(reconciliation.get("outstanding").decimalValue()).isEqualByComparingTo("100.00");
    }

    private JsonNode uncheckedSend(String method, String path, String organizationKey,
                                   String idempotencyKey, String body, int expected) {
        try {
            return send(method, path, organizationKey, idempotencyKey, body, expected);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode send(String method, String path, String organizationKey,
                          String idempotencyKey, String body, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Organization-Key", organizationKey).header("Content-Type", "application/json");
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(expected);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
}
