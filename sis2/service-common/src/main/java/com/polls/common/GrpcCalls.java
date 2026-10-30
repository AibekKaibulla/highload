package com.polls.common;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.function.Supplier;

/** Runs a unary gRPC handler and converts exceptions into gRPC status codes. */
public final class GrpcCalls {
    private static final Logger log = LoggerFactory.getLogger(GrpcCalls.class);

    private GrpcCalls() {
    }

    public static <T> void unary(StreamObserver<T> observer, Supplier<T> handler) {
        T result;
        try {
            result = handler.get();
        } catch (ApiException e) {
            observer.onError(toStatus(e));
            return;
        } catch (DataAccessException | TransactionException e) {
            observer.onError(toStatus(DbErrors.translate(e)));
            return;
        } catch (RuntimeException e) {
            log.error("Unexpected error in gRPC handler", e);
            observer.onError(toStatus(new ApiException(ErrorCode.INTERNAL, "internal error")));
            return;
        }
        try {
            observer.onNext(result);
            observer.onCompleted();
        } catch (RuntimeException e) {
            // Client already gone (deadline hit / cancelled): nothing to send the result to.
            log.debug("Could not send gRPC response: {}", e.getMessage());
        }
    }

    private static RuntimeException toStatus(ApiException e) {
        return Status.fromCode(e.code().grpcCode).withDescription(e.getMessage()).asRuntimeException();
    }
}
