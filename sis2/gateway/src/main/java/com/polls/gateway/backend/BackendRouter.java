package com.polls.gateway.backend;

import com.polls.gateway.resilience.SettingsHolder;
import org.springframework.stereotype.Component;

/** Picks the internal transport (gRPC or REST/JSON) from the current settings. */
@Component
public class BackendRouter {
    private final SettingsHolder settings;
    private final GrpcBackend grpc;
    private final RestBackend rest;

    public BackendRouter(SettingsHolder settings, GrpcBackend grpc, RestBackend rest) {
        this.settings = settings;
        this.grpc = grpc;
        this.rest = rest;
    }

    public Backends.PollBackend polls() {
        return "rest".equals(settings.get().protocol()) ? rest : grpc;
    }

    public Backends.VoteBackend votes() {
        return "rest".equals(settings.get().protocol()) ? rest : grpc;
    }
}
