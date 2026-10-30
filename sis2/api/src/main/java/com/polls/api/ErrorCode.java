package com.polls.api;

import io.grpc.Status;

/** One error vocabulary for both transports: maps to a gRPC status and to an HTTP status. */
public enum ErrorCode {
    INVALID_ARGUMENT(400, Status.Code.INVALID_ARGUMENT),
    UNAUTHENTICATED(401, Status.Code.UNAUTHENTICATED),
    NOT_FOUND(404, Status.Code.NOT_FOUND),
    ALREADY_EXISTS(409, Status.Code.ALREADY_EXISTS),
    FAILED_PRECONDITION(422, Status.Code.FAILED_PRECONDITION),
    INTERNAL(500, Status.Code.INTERNAL),
    UNAVAILABLE(503, Status.Code.UNAVAILABLE),
    DEADLINE_EXCEEDED(504, Status.Code.DEADLINE_EXCEEDED);

    public final int httpStatus;
    public final Status.Code grpcCode;

    ErrorCode(int httpStatus, Status.Code grpcCode) {
        this.httpStatus = httpStatus;
        this.grpcCode = grpcCode;
    }

    public static ErrorCode fromGrpc(Status.Code code) {
        for (ErrorCode c : values()) {
            if (c.grpcCode == code) return c;
        }
        return switch (code) {
            case RESOURCE_EXHAUSTED, ABORTED -> UNAVAILABLE;
            case CANCELLED -> DEADLINE_EXCEEDED;
            default -> INTERNAL;
        };
    }

    public static ErrorCode fromHttp(int status) {
        for (ErrorCode c : values()) {
            if (c.httpStatus == status) return c;
        }
        return switch (status) {
            case 429, 502 -> UNAVAILABLE;
            default -> status >= 500 ? INTERNAL : INVALID_ARGUMENT;
        };
    }
}
