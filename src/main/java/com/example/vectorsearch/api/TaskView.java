package com.example.vectorsearch.api;

import com.example.vectorsearch.persistence.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskView(UUID taskId, String documentId, TaskStatus status, Instant createdAt,
        Instant startedAt, Instant finishedAt, int attemptCount, Error error, DocumentDetail document) {
    public record Error(String code, String message) { }
}
