package com.polls.gateway.backend;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Body size in bytes of every internal request/response: Protobuf vs JSON on live traffic. */
@Component
public class PayloadMetrics {
    private final MeterRegistry metrics;

    public PayloadMetrics(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public void record(String method, String protocol, String direction, long bytes) {
        DistributionSummary.builder("gateway.backend.payload.bytes")
                .baseUnit("bytes")
                .tag("method", method).tag("protocol", protocol).tag("direction", direction)
                .register(metrics)
                .record(bytes);
    }
}
