package com.polls.gateway.resilience;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The ONLY place in the system that retries (no retry amplification).
 * Rules:
 *  - one total deadline per public request; every attempt gets only what is left;
 *  - retry only transient errors (UNAVAILABLE; DEADLINE_EXCEEDED only if enabled);
 *  - never retry 4xx-type errors, the second attempt would fail the same way;
 *  - exponential backoff with full jitter, capped, and only if the budget still allows it.
 * All our internal calls are safe to retry: votes via PK(poll_id, user_id),
 * poll creation via the idempotency key, reads by nature.
 */
@Component
public class ResilientCaller {
    /** Don't start an attempt with less time than this: it would almost surely time out. */
    private static final long MIN_ATTEMPT_MS = 5;

    private final SettingsHolder settings;
    private final MeterRegistry metrics;

    public ResilientCaller(SettingsHolder settings, MeterRegistry metrics) {
        this.settings = settings;
        this.metrics = metrics;
    }

    @FunctionalInterface
    public interface Attempt<T> {
        T run(long timeoutMs);
    }

    public <T> T call(String method, Attempt<T> attempt) {
        GatewaySettings s = settings.get();
        long start = System.nanoTime();
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(s.deadlineMs());
        ApiException last = null;
        int attemptNo = 0;

        while (attemptNo < s.maxAttempts()) {
            long remaining = remainingMs(deadline);
            if (remaining < MIN_ATTEMPT_MS) break;
            attemptNo++;
            long timeout = s.attemptTimeoutMs() > 0 ? Math.min(s.attemptTimeoutMs(), remaining) : remaining;

            long attemptStart = System.nanoTime();
            try {
                T result = attempt.run(timeout);
                recordAttempt(method, s.protocol(), "OK", attemptStart);
                recordCall(method, s.protocol(), "OK", attemptNo, start);
                return result;
            } catch (ApiException e) {
                recordAttempt(method, s.protocol(), e.code().name(), attemptStart);
                last = e;
                if (!isRetryable(e.code(), s) || attemptNo >= s.maxAttempts()) break;

                long backoff = backoffMs(s, attemptNo);
                if (remainingMs(deadline) - backoff < MIN_ATTEMPT_MS) break; // no budget left for another try
                Counter.builder("gateway.backend.retries")
                        .tag("method", method).tag("protocol", s.protocol()).tag("reason", e.code().name())
                        .register(metrics).increment();
                sleep(backoff);
            }
        }

        if (last == null || (remainingMs(deadline) < MIN_ATTEMPT_MS && isRetryable(last.code(), s))) {
            last = new ApiException(ErrorCode.DEADLINE_EXCEEDED,
                    "deadline of " + s.deadlineMs() + " ms exceeded after " + attemptNo + " attempt(s)");
        }
        recordCall(method, s.protocol(), last.code().name(), attemptNo, start);
        throw last;
    }

    private static boolean isRetryable(ErrorCode code, GatewaySettings s) {
        return code == ErrorCode.UNAVAILABLE || (code == ErrorCode.DEADLINE_EXCEEDED && s.retryOnDeadline());
    }

    /** Window doubles per attempt (10, 20, 40 ms...), capped; full jitter picks a random point in it. */
    static long backoffMs(GatewaySettings s, int attemptNo) {
        long window = Math.min(s.maxBackoffMs(), s.baseBackoffMs() << Math.min(attemptNo - 1, 20));
        if (!s.jitter() || window == 0) return window;
        return ThreadLocalRandom.current().nextLong(window + 1);
    }

    private static long remainingMs(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.UNAVAILABLE, "interrupted");
        }
    }

    private void recordAttempt(String method, String protocol, String outcome, long startNanos) {
        Timer.builder("gateway.backend.attempt")
                .description("Latency of a single attempt to a backend")
                .tag("method", method).tag("protocol", protocol).tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(metrics)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private void recordCall(String method, String protocol, String outcome, int attempts, long startNanos) {
        Timer.builder("gateway.backend.call")
                .description("Latency of a logical call including retries and backoff")
                .tag("method", method).tag("protocol", protocol).tag("outcome", outcome)
                .tag("attempts", String.valueOf(attempts))
                .publishPercentileHistogram()
                .register(metrics)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
