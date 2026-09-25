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
        if (key == null || key.isBlank() || key.length() > 120) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must contain at most 120 characters");
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:value, 0))::text")
                .param("value", organizationId + ":" + operation + ":" + key)
                .query(String.class)
                .single();
    }

    public String fingerprint(Object value) {
        return Hashing.sha256(writeJson(value));
    }

    public <T> @Nullable T replay(UUID organizationId, String operation, String key, String fingerprint, Class<T> type) {
        return jdbc.sql("""
                        select request_fingerprint, response_json::text
                        from idempotency_record
                        where organization_id = :organizationId
                          and operation = :operation
                          and idempotency_key = :idempotencyKey
                        """)
                .param("organizationId", organizationId)
                .param("operation", operation)
                .param("idempotencyKey", key)
                .query((row, ignored) -> {
                    requireMatchingFingerprint(fingerprint, row.getString(1));
                    return readJson(row.getString(2), type);
                })
                .optional()
                .orElse(null);
    }

    public void remember(UUID organizationId, String operation, String key, String fingerprint, Object response) {
        jdbc.sql("""
                        insert into idempotency_record(
                            organization_id, operation, idempotency_key,
                            request_fingerprint, response_json, created_at
                        )
                        values (
                            :organizationId, :operation, :idempotencyKey,
                            :fingerprint, cast(:response as jsonb), :createdAt
                        )
                        """)
                .param("organizationId", organizationId)
                .param("operation", operation)
                .param("idempotencyKey", key)
                .param("fingerprint", fingerprint)
                .param("response", writeJson(response))
                .param("createdAt", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)))
                .update();
    }

    private void requireMatchingFingerprint(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency key was already used with a different request");
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize idempotency data", exception);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not deserialize idempotency data", exception);
        }
    }
}
