package io.prudent.wallet.organization;

import io.prudent.wallet.platform.Hashing;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
final class OrganizationFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("X-Organization-Key");
        if (key == null || key.isBlank()) {
            unauthorized(response, "MISSING_ORGANIZATION_KEY", "X-Organization-Key is required");
            return;
        }
        var organizationId = jdbc.sql("""
                        select id
                        from organization
                        where api_key_hash = :apiKeyHash
                        """)
                .param("apiKeyHash", Hashing.sha256(key))
                .query(UUID.class)
                .optional();
        if (organizationId.isEmpty()) {
            unauthorized(response, "INVALID_ORGANIZATION_KEY", "Organization key is invalid");
            return;
        }
        try {
            OrganizationContext.set(organizationId.get());
            chain.doFilter(request, response);
        } finally {
            OrganizationContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
