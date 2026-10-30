package com.polls.common;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Starts a gRPC (HTTP/2) server with every gRPC service bean in the app.
 * Handlers block on JDBC, so each call runs on a virtual thread: thousands of
 * in-flight calls cost almost nothing, and the real limit is the DB pool (Little's Law).
 */
@Component
public class GrpcServerRunner implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerRunner.class);

    private final List<BindableService> services;
    private final int port;
    private ExecutorService executor;
    private Server server;

    public GrpcServerRunner(List<BindableService> services, @Value("${grpc.port:9090}") int port) {
        this.services = services;
        this.port = port;
    }

    @Override
    public void start() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        NettyServerBuilder builder = NettyServerBuilder.forPort(port).executor(executor);
        services.forEach(builder::addService);
        try {
            server = builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start gRPC server on port " + port, e);
        }
        log.info("gRPC server listening on {} with {} service(s)", port, services.size());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
