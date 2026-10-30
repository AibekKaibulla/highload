package com.polls.common;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.dto.Dtos.ErrorBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Same error mapping as GrpcCalls, but for the internal REST/JSON baseline. */
@RestControllerAdvice
public class InternalErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(InternalErrorAdvice.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException e) {
        return ResponseEntity.status(e.code().httpStatus).body(ErrorBody.of(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorBody> db(RuntimeException e) {
        return api(DbErrors.translate(e));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception e) {
        return api(new ApiException(ErrorCode.INVALID_ARGUMENT, e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorBody> other(RuntimeException e) {
        log.error("Unexpected error in REST handler", e);
        return api(new ApiException(ErrorCode.INTERNAL, "internal error"));
    }
}
