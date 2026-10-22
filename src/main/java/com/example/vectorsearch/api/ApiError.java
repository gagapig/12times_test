package com.example.vectorsearch.api;

import java.util.List;

public record ApiError(String code, String message, String requestId, List<FieldError> details) {
    public record FieldError(String field, String message) { }
}
