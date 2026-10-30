package com.polls.common;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import io.grpc.Context;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fault injection for the deadline/retry experiments. Off by default.
 * Changed at runtime with PUT /admin/chaos. Applied the same way to gRPC and REST.
 *
 *  latency_ms + latency_rate  -> a share of calls is slow (tail latency)
 *  fail_rate                  -> a share of calls fails BEFORE doing work (UNAVAILABLE, safe to retry)
 *  fail_after_rate            -> the work is COMMITTED, then the response is "lost" (UNAVAILABLE).
 *                                This is the "unknown outcome" case that idempotency must handle.
 */
@Component
public class Chaos {

    public record Settings(long latencyMs, double latencyRate, double failRate, double failAfterRate) {
        public Settings {
            if (latencyMs < 0 || latencyMs > 10_000) throw new IllegalArgumentException("latency_ms must be 0..10000");
            for (double r : new double[]{latencyRate, failRate, failAfterRate}) {
                if (r < 0 || r > 1) throw new IllegalArgumentException("rates must be 0..1");
            }
        }
    }

    private final AtomicReference<Settings> settings = new AtomicReference<>(new Settings(0, 0, 0, 0));
    private final MeterRegistry metrics;

    public Chaos(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public Settings get() {
        return settings.get();
    }

    public void set(Settings next) {
        settings.set(next);
    }

    /** Call at the start of an operation. */
    public void before() {
        Settings s = settings.get();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (s.latencyRate() > 0 && rnd.nextDouble() < s.latencyRate()) {
            count("latency");
            sleep(s.latencyMs());
            // gRPC propagates cancellation: if the caller's deadline passed while we were slow,
            // we know it and skip the work. Plain REST can't tell us, so the work still runs.
            if (Context.current().isCancelled()) {
                count("work_skipped_cancelled");
                throw new ApiException(ErrorCode.DEADLINE_EXCEEDED, "caller already gave up, work skipped");
            }
        }
        if (s.failRate() > 0 && rnd.nextDouble() < s.failRate()) {
            count("fail_before");
            throw new ApiException(ErrorCode.UNAVAILABLE, "chaos: injected failure before work");
        }
    }

    /** Call after the work is committed. */
    public void after() {
        Settings s = settings.get();
        if (s.failAfterRate() > 0 && ThreadLocalRandom.current().nextDouble() < s.failAfterRate()) {
            count("fail_after");
            throw new ApiException(ErrorCode.UNAVAILABLE, "chaos: work committed but response lost");
        }
    }

    private void count(String type) {
        Counter.builder("chaos.injected").tag("type", type).register(metrics).increment();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
