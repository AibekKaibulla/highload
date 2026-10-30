package com.polls.gateway.web;

import com.polls.api.ApiException;
import com.polls.api.ErrorCode;
import com.polls.api.dto.Dtos.ErrorBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Public error format: {"error": {"code": "...", "message": "..."}} with the matching HTTP status. */
@RestControllerAdvice
public class GatewayErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorAdvice.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> api(ApiException e) {
        return ResponseEntity.status(e.code().httpStatus).body(ErrorBody.of(e.code().name(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> badRequest(Exception e) {
        return api(new ApiException(ErrorCode.INVALID_ARGUMENT, e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorBody> other(RuntimeException e) {
        log.error("Unexpected error", e);
        return api(new ApiException(ErrorCode.INTERNAL, "internal error"));
    }
}
