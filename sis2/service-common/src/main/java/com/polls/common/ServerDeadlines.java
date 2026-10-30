package com.polls.common;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import io.grpc.Context;
import io.grpc.Deadline;

import java.util.concurrent.TimeUnit;

/**
 * Reads how much time the caller still allows for this request.
 * gRPC: the deadline travels with the call automatically.
 * REST: the gateway sends it in the X-Deadline-Ms header.
 * The value is then used as the Postgres statement_timeout.
 */
public final class ServerDeadlines {
    /** Upper bound when the caller sends no deadline, so no request can run forever. */
    public static final long MAX_MS = 1000;

    private ServerDeadlines() {
    }

    public static long fromGrpc() {
        Deadline d = Context.current().getDeadline();
        long ms = d == null ? MAX_MS : Math.min(MAX_MS, d.timeRemaining(TimeUnit.MILLISECONDS));
        return check(ms);
    }

    public static long fromHeader(String header) {
        long ms = MAX_MS;
        if (header != null && !header.isBlank()) {
            try {
                ms = Math.min(MAX_MS, Long.parseLong(header.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "bad X-Deadline-Ms header");
            }
        }
        return check(ms);
    }

    /** If the budget is already used up, the caller has given up: don't do the work at all. */
    private static long check(long ms) {
        if (ms <= 0) {
            throw new ApiException(ErrorCode.DEADLINE_EXCEEDED, "deadline expired before work started");
        }
        return ms;
    }
}
