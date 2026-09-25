package io.prudent.wallet.platform;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public final class IdempotencyStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public void lock(UUID organizationId, String operation, String key) {
        if (key == null || key.isBlank() || key.length() > 120)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is required and must contain at most 120 characters");
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:value, 0))::text")
                .param("value", organizationId + ":" + operation + ":" + key).query(String.class).single();
    }

    public String fingerprint(Object value) {
        try { return Hashing.sha256(json.writeValueAsString(value)); }
        catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }

    public <T> @Nullable T replay(UUID organizationId, String operation, String key, String fingerprint, Class<T> type) {
        return jdbc.sql("select request_fingerprint, response_json::text from idempotency_record where organization_id=:org and operation=:operation and idempotency_key=:key")
                .param("org", organizationId).param("operation", operation).param("key", key)
                .query((row, ignored) -> {
                    if (!fingerprint.equals(row.getString(1)))
                        throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency key was already used with a different request");
                    try { return json.readValue(row.getString(2), type); }
                    catch (JacksonException exception) { throw new IllegalStateException(exception); }
                }).optional().orElse(null);
    }

    public void remember(UUID organizationId, String operation, String key, String fingerprint, Object response) {
        try {
            jdbc.sql("insert into idempotency_record(organization_id,operation,idempotency_key,request_fingerprint,response_json,created_at) values (:org,:operation,:key,:fingerprint,cast(:response as jsonb),:now)")
                    .param("org", organizationId).param("operation", operation).param("key", key).param("fingerprint", fingerprint)
                    .param("response", json.writeValueAsString(response)).param("now", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))).update();
        } catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }
}
