package io.github.lz007001cn.veriqra.admin;

import jakarta.servlet.ServletContext;
import java.util.Objects;

/** Separate composition for additive Administration features; existing WebServices stays stable. */
public record AdminServices(AdminUserService users,CreditService credits,AdminLogService logs,
                            TelemetryService telemetry,long startedAtMillis) {
    public static final String ATTRIBUTE=AdminServices.class.getName();
    public AdminServices { Objects.requireNonNull(users);Objects.requireNonNull(credits);Objects.requireNonNull(logs);Objects.requireNonNull(telemetry); }
    public static AdminServices from(ServletContext context) {
        return Objects.requireNonNull((AdminServices)context.getAttribute(ATTRIBUTE),"Administration services not initialized");
    }
}
