package com.polls.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deadline / retry / protocol settings. Defaults are in application.yml. */
@ConfigurationProperties(prefix = "gateway")
public record GatewaySettings(
        String protocol,
        long deadlineMs,
        long attemptTimeoutMs,
        int maxAttempts,
        long baseBackoffMs,
        long maxBackoffMs,
        boolean jitter,
        boolean retryOnDeadline) {

    public GatewaySettings {
        if (!"grpc".equals(protocol) && !"rest".equals(protocol)) {
            throw new IllegalArgumentException("protocol must be grpc or rest");
        }
        if (deadlineMs <= 0) throw new IllegalArgumentException("deadline_ms must be > 0");
        if (maxAttempts < 1 || maxAttempts > 5) throw new IllegalArgumentException("max_attempts must be 1..5");
        if (attemptTimeoutMs < 0 || baseBackoffMs < 0 || maxBackoffMs < baseBackoffMs) {
            throw new IllegalArgumentException("invalid timeout/backoff values");
        }
    }
}
