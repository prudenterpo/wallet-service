package io.prudent.wallet.organization;

import java.util.UUID;

public final class OrganizationContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private OrganizationContext() {
    }

    public static UUID requiredId() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("Organization context is not available");
        }
        return id;
    }

    static void set(UUID id) {
        CURRENT.set(id);
    }

    static void clear() {
        CURRENT.remove();
    }
}
