package com.slackmsg.util;

import java.util.UUID;

/**
 * Thread-local tenant context. Set by JWT filter, used by all services.
 * Every request runs in a tenant-scoped context.
 */
public class TenantContext {

    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<String> DISPLAY_NAME = new ThreadLocal<>();

    public static void setTenantId(UUID tenantId) { TENANT_ID.set(tenantId); }
    public static UUID getTenantId() { return TENANT_ID.get(); }

    public static void setUserId(UUID userId) { USER_ID.set(userId); }
    public static UUID getUserId() { return USER_ID.get(); }

    public static void setUserRole(String role) { USER_ROLE.set(role); }
    public static String getUserRole() { return USER_ROLE.get(); }

    public static void setDisplayName(String name) { DISPLAY_NAME.set(name); }
    public static String getDisplayName() { return DISPLAY_NAME.get(); }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        USER_ROLE.remove();
        DISPLAY_NAME.remove();
    }
}
