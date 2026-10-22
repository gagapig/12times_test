package com.example.vectorsearch.api;

import com.example.vectorsearch.persistence.TaskStatus;
import java.util.UUID;

public record SubmitResponse(UUID taskId, String documentId, TaskStatus status, String taskUrl) { }
