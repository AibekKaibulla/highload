package com.polls.gateway.resilience;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/** Current settings; replaced at runtime by PUT /admin/settings so experiments don't need a restart. */
@Component
public class SettingsHolder {
    private final AtomicReference<GatewaySettings> current;

    public SettingsHolder(GatewaySettings initial) {
        this.current = new AtomicReference<>(initial);
    }

    public GatewaySettings get() {
        return current.get();
    }

    public void set(GatewaySettings settings) {
        current.set(settings);
    }
}
