package com.example.vectorsearch.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage(), request, List.of()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException ex, HttpServletRequest request) {
        // Only the future document insertion boundary can identify a duplicate document ID.
        boolean integrity = ex instanceof DataIntegrityViolationException;
        LOG.error("Database failure requestId={} type={}", request.getAttribute(RequestBoundaryFilter.REQUEST_ID),
                ex.getClass().getSimpleName());
        return ResponseEntity.status(integrity ? 500 : 503).body(error(
                integrity ? "INTERNAL_ERROR" : "STORAGE_UNAVAILABLE",
                integrity ? "数据约束异常" : "数据库暂不可用", request, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        LOG.error("Unexpected failure requestId={} type={}", request.getAttribute(RequestBoundaryFilter.REQUEST_ID),
                ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(error("INTERNAL_ERROR", "服务内部错误", request, List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.FieldError(e.getField(), e.getDefaultMessage())).toList();
        return new ResponseEntity<>(error("INVALID_ARGUMENT", "请求参数不合法", servlet(request), details), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 400 -> ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                    ? "MALFORMED_JSON" : "INVALID_ARGUMENT";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL_ERROR";
        };
        return new ResponseEntity<>(error(code, "请求未能处理", servlet(request), List.of()), headers, status);
    }

    private static HttpServletRequest servlet(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    private static ApiError error(String code, String message, HttpServletRequest request,
                                  List<ApiError.FieldError> details) {
        return new ApiError(code, message, (String) request.getAttribute(RequestBoundaryFilter.REQUEST_ID), details);
    }
}
