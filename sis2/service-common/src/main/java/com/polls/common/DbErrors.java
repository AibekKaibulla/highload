package com.polls.common;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;

/** Turns database exceptions into API error codes, using the Postgres SQLSTATE. */
public final class DbErrors {
    private DbErrors() {
    }

    public static ApiException translate(RuntimeException e) {
        if (e instanceof CannotGetJdbcConnectionException || e instanceof CannotCreateTransactionException) {
            // Pool exhausted (waited connection-timeout) or DB down -> transient, safe to retry.
            return new ApiException(ErrorCode.UNAVAILABLE, "database unavailable or connection pool exhausted");
        }
        Throwable cause = e instanceof DataAccessException dae ? dae.getMostSpecificCause() : e.getCause();
        if (cause instanceof SQLException sql && sql.getSQLState() != null) {
            String state = sql.getSQLState();
            switch (state) {
                case "57014": // query_canceled: statement_timeout from our deadline
                    return new ApiException(ErrorCode.DEADLINE_EXCEEDED, "query cancelled: deadline reached");
                case "23503": // foreign_key_violation
                    return new ApiException(ErrorCode.NOT_FOUND, "referenced poll, option or user does not exist");
                case "23505":
                    return new ApiException(ErrorCode.ALREADY_EXISTS, "duplicate");
                case "22P02", "22001", "23514":
                    return new ApiException(ErrorCode.INVALID_ARGUMENT, "invalid value");
                default:
                    if (state.startsWith("08") || state.startsWith("53") || state.startsWith("57P")) {
                        return new ApiException(ErrorCode.UNAVAILABLE, "database unavailable");
                    }
            }
        }
        return new ApiException(ErrorCode.INTERNAL, "database error");
    }
}
