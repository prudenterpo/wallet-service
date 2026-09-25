package io.prudent.wallet.organization;

import io.prudent.wallet.platform.Hashing;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(LocalOrganizationBootstrap.Properties.class)
@RequiredArgsConstructor
final class LocalOrganizationBootstrap implements ApplicationRunner {
    @ConfigurationProperties("wallet.bootstrap")
    record Properties(
            boolean enabled,
            List<Organization> organizations) {
        record Organization(
                String name,
                @Nullable String apiKey) {}
    }

    private final JdbcClient jdbc;
    private final Properties properties;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }

        properties.organizations().stream()
                .filter(this::hasApiKey)
                .forEach(this::insertIfAbsent);
    }

    private boolean hasApiKey(Properties.Organization organization) {
        return organization.apiKey() != null && !organization.apiKey().isBlank();
    }

    private void insertIfAbsent(Properties.Organization organization) {
        String apiKey = organization.apiKey();
        if (apiKey == null) {
            return;
        }

        jdbc.sql("""
                        insert into organization(id, name, api_key_hash, created_at)
                        values (:id, :name, :apiKeyHash, :createdAt)
                        on conflict (api_key_hash) do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("name", organization.name())
                .param("apiKeyHash", Hashing.sha256(apiKey))
                .param("createdAt", OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)))
                .update();
    }
}
