package com.example.vectorsearch.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Field access only. No public setters or whole-entity update API. */
@Entity
@Table(name = "vector_task")
public class VectorTask {
    @Id
    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "document_id", length = 64, nullable = false, unique = true, updatable = false)
    private String documentId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 16, nullable = false)
    private TaskStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "recovery_count", nullable = false)
    private int recoveryCount;

    @Column(name = "last_recovered_at")
    private Instant lastRecoveredAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected VectorTask() { }

    public VectorTask(UUID taskId, String documentId, Instant createdAt) {
        this.taskId = taskId;
        this.documentId = documentId;
        this.createdAt = createdAt;
        this.status = TaskStatus.QUEUED;
    }

    public UUID getTaskId() { return taskId; }
    public String getDocumentId() { return documentId; }
    public TaskStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getRecoveryCount() { return recoveryCount; }
    public Instant getLastRecoveredAt() { return lastRecoveredAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
