package com.example.vectorsearch.api;

import com.example.vectorsearch.persistence.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record DocumentDetail(String documentId, String channel, Instant submittedAt, Instant completedAt,
        boolean vectorReady, boolean invalidated, Instant invalidatedAt, long hitCount,
        UUID taskId, TaskStatus taskStatus, String text) { }
