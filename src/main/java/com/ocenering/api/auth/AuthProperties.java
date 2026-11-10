package com.ocenering.api.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        boolean cookieSecure,
        String adminEmail,
        String adminPassword,
        @DefaultValue("12") int sessionTtlHours,
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("15") int lockDurationMinutes,
        @DefaultValue("12") int minPasswordLength) {

    public Duration sessionTtl() {
        return Duration.ofHours(sessionTtlHours);
    }

    public Duration lockDuration() {
        return Duration.ofMinutes(lockDurationMinutes);
    }
}
