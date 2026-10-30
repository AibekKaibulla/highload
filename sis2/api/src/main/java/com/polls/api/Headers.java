package com.polls.api;

public final class Headers {
    /** REST has no built-in deadline, so the gateway sends the remaining budget in ms. gRPC does this itself. */
    public static final String DEADLINE_MS = "X-Deadline-Ms";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String USER_ID = "X-User-Id";
    public static final String REPLAYED = "Idempotent-Replayed";

    private Headers() {
    }
}
