package com.example.vectorsearch.task;

import com.example.vectorsearch.api.*;
import com.example.vectorsearch.persistence.*;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Short database transactions. The queue is never the state authority. */
@Component
public class TaskStore {
    public record Claim(UUID taskId, String documentId, int attemptCount, String text) { }
    public record Cursor(Instant createdAt, UUID taskId) { }
    public record TaskState(TaskStatus status, int attemptCount, boolean vectorReady) { }

    private static final String COMMON_COLUMNS = "d.document_id, d.channel, d.submitted_at, "
            + "d.vector_completed_at, d.vector_ready, d.invalidated, d.invalidated_at, d.hit_count, "
            + "t.task_id, t.status, t.created_at, t.started_at, t.finished_at, t.attempt_count, "
            + "t.error_code, t.error_message";
    private static final String JOIN = " FROM document_record d JOIN vector_task t ON t.document_id=d.document_id ";
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final DatabaseOperation database;

    public TaskStore(EntityManager em, JdbcTemplate jdbc, DocumentRepository documents, DatabaseOperation database) {
        this.em = em;
        this.jdbc = jdbc;
        this.documents = documents;
        this.database = database;
    }

    public void create(String documentId, String text, String channel, UUID taskId,
                       Instant submittedAt, int maxPending) {
        database.write(() -> {
            if (documents.existsById(documentId)) {
                throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_EXISTS", "文档 ID 已存在");
            }
            Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM vector_task WHERE status IN ('QUEUED','RUNNING')",
                    Long.class);
            if (pending == null || pending >= maxPending) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TASK_BACKLOG_FULL", "未完成任务已达上限");
            }
            em.persist(new DocumentRecord(documentId, text, channel, submittedAt));
            em.persist(new VectorTask(taskId, documentId, submittedAt));
            em.flush();
            return null;
        });
    }

    public Claim claim(UUID taskId, int maxAttempts) {
        return database.write(() -> {
            int updated = jdbc.update("UPDATE vector_task SET status='RUNNING', attempt_count=attempt_count+1, "
                    + "started_at=? WHERE task_id=? AND status='QUEUED' AND attempt_count<?",
                    utc(Instant.now()), taskId, maxAttempts);
            if (updated != 1) {
                jdbc.update("UPDATE vector_task SET status='FAILED', finished_at=?, "
                                + "error_code='RECOVERY_LIMIT_EXCEEDED', error_message='执行次数已达上限' "
                                + "WHERE task_id=? AND status='QUEUED' AND attempt_count>=?",
                        utc(Instant.now()), taskId, maxAttempts);
                return null;
            }
            return jdbc.queryForObject("SELECT t.document_id, t.attempt_count, d.original_text "
                            + "FROM vector_task t JOIN document_record d ON d.document_id=t.document_id WHERE t.task_id=?",
                    (rs, row) -> new Claim(taskId, rs.getString(1), rs.getInt(2), rs.getString(3)), taskId);
        });
    }

    public void publish(Claim claim, byte[] vector, String version, Instant completedAt) {
        database.write(() -> {
            int taskUpdated = jdbc.update("UPDATE vector_task SET status='SUCCEEDED', finished_at=? "
                            + "WHERE task_id=? AND status='RUNNING' AND attempt_count=?",
                    utc(completedAt), claim.taskId(), claim.attemptCount());
            if (taskUpdated == 0) {
                TaskState persisted = stateInCurrentTransaction(claim.taskId());
                if (persisted != null && persisted.status() == TaskStatus.SUCCEEDED
                        && persisted.attemptCount() == claim.attemptCount() && persisted.vectorReady()) {
                    return null; // A previously uncertain commit completed; do not publish twice.
                }
                throw new IllegalStateException("Task claim changed before publication");
            }
            int docUpdated = jdbc.update("UPDATE document_record SET vector_bytes=?, vector_version=?, "
                            + "vector_ready=true, vector_completed_at=? "
                            + "WHERE document_id=? AND vector_ready=false",
                    vector, version, utc(completedAt), claim.documentId());
            if (docUpdated != 1) {
                throw new IllegalStateException("Document vector could not be published");
            }
            return null;
        });
    }

    public void fail(Claim claim, String code, String safeReason, Instant finishedAt) {
        database.write(() -> {
            int updated = jdbc.update("UPDATE vector_task SET status='FAILED', finished_at=?, error_code=?, "
                            + "error_message=? WHERE task_id=? AND status='RUNNING' AND attempt_count=?",
                    utc(finishedAt), code, safeReason, claim.taskId(), claim.attemptCount());
            if (updated == 0) {
                TaskState persisted = stateInCurrentTransaction(claim.taskId());
                if (persisted != null && persisted.status() == TaskStatus.FAILED
                        && persisted.attemptCount() == claim.attemptCount()) {
                    return null;
                }
                throw new IllegalStateException("Task claim changed before failure recording");
            }
            return null;
        });
    }

    public void recover(int maxAttempts) {
        database.write(() -> {
            Instant now = Instant.now();
            jdbc.update("UPDATE vector_task SET status='FAILED', finished_at=?, "
                    + "error_code='RECOVERY_LIMIT_EXCEEDED', error_message='执行次数已达上限', "
                    + "recovery_count=recovery_count+1, last_recovered_at=? "
                    + "WHERE status='RUNNING' AND attempt_count>=?", utc(now), utc(now), maxAttempts);
            jdbc.update("UPDATE vector_task SET status='QUEUED', started_at=NULL, finished_at=NULL, "
                    + "error_code=NULL, error_message=NULL, recovery_count=recovery_count+1, last_recovered_at=? "
                    + "WHERE status='RUNNING' AND attempt_count<?", utc(now), maxAttempts);
            return null;
        });
    }

    public List<Cursor> queuedAfter(Cursor after, int limit) {
        return database.read(() -> {
            if (after == null) {
                return jdbc.query("SELECT created_at,task_id FROM vector_task WHERE status='QUEUED' "
                                + "ORDER BY created_at,task_id LIMIT ?",
                        (rs, row) -> new Cursor(instant(rs, "created_at"), rs.getObject("task_id", UUID.class)), limit);
            }
            return jdbc.query("SELECT created_at,task_id FROM vector_task WHERE status='QUEUED' "
                            + "AND (created_at>? OR (created_at=? AND task_id>?)) "
                            + "ORDER BY created_at,task_id LIMIT ?",
                    (rs, row) -> new Cursor(instant(rs, "created_at"), rs.getObject("task_id", UUID.class)),
                    utc(after.createdAt()), utc(after.createdAt()), after.taskId(), limit);
        });
    }

    public Optional<TaskState> state(UUID taskId) {
        return database.read(() -> Optional.ofNullable(stateInCurrentTransaction(taskId)));
    }

    public Optional<Claim> runningClaim(UUID taskId) {
        return database.read(() -> jdbc.query("SELECT t.document_id,t.attempt_count,d.original_text "
                        + "FROM vector_task t JOIN document_record d ON d.document_id=t.document_id "
                        + "WHERE t.task_id=? AND t.status='RUNNING'",
                (rs, row) -> new Claim(taskId, rs.getString(1), rs.getInt(2), rs.getString(3)), taskId)
                .stream().findFirst());
    }

    private TaskState stateInCurrentTransaction(UUID taskId) {
        var rows = jdbc.query("SELECT t.status,t.attempt_count,d.vector_ready FROM vector_task t "
                        + "JOIN document_record d ON d.document_id=t.document_id WHERE t.task_id=?",
                (rs, row) -> new TaskState(TaskStatus.valueOf(rs.getString(1)), rs.getInt(2), rs.getBoolean(3)), taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Optional<DocumentDetail> document(String id) {
        return database.read(() -> jdbc.query("SELECT " + COMMON_COLUMNS + ", d.original_text" + JOIN
                + " WHERE d.document_id=?", (rs, row) -> map(rs, true).detail(), id).stream().findFirst());
    }

    public Optional<TaskView> task(UUID taskId) {
        return database.read(() -> jdbc.query("SELECT " + COMMON_COLUMNS + ", d.original_text" + JOIN
                + " WHERE t.task_id=?", (rs, row) -> map(rs, true).task(), taskId).stream().findFirst());
    }

    public Optional<TaskView> taskForDocument(String id) {
        return database.read(() -> jdbc.query("SELECT " + COMMON_COLUMNS + ", d.original_text" + JOIN
                + " WHERE d.document_id=?", (rs, row) -> map(rs, true).task(), id).stream().findFirst());
    }

    public DocumentPage list(int page, int size, String channel, Boolean invalidated,
                             Boolean vectorReady, TaskStatus status) {
        return database.snapshotRead(() -> {
            StringBuilder where = new StringBuilder(" WHERE 1=1");
            List<Object> values = new ArrayList<>();
            if (channel != null) { where.append(" AND d.channel=?"); values.add(channel); }
            if (invalidated != null) { where.append(" AND d.invalidated=?"); values.add(invalidated); }
            if (vectorReady != null) { where.append(" AND d.vector_ready=?"); values.add(vectorReady); }
            if (status != null) { where.append(" AND t.status=?"); values.add(status.name()); }
            Long count = jdbc.queryForObject("SELECT COUNT(*)" + JOIN + where, Long.class, values.toArray());
            List<Object> pageValues = new ArrayList<>(values);
            pageValues.add((long) page * size);
            pageValues.add(size);
            List<DocumentSummary> rows = jdbc.query("SELECT " + COMMON_COLUMNS + JOIN + where
                            + " ORDER BY d.submitted_at DESC,d.document_id ASC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                    (rs, row) -> map(rs, false).summary(), pageValues.toArray());
            long total = count == null ? 0 : count;
            return new DocumentPage(rows, page, size, (total), (total + size - 1) / size);
        });
    }

    public boolean probe() {
        return jdbc.queryForObject("SELECT 1", Integer.class) == 1;
    }

    private static Joined map(ResultSet rs, boolean withText) throws SQLException {
        return new Joined(rs.getString("document_id"), rs.getString("channel"),
                instant(rs, "submitted_at"), instant(rs, "vector_completed_at"),
                rs.getBoolean("vector_ready"), rs.getBoolean("invalidated"),
                instant(rs, "invalidated_at"), rs.getLong("hit_count"),
                rs.getObject("task_id", UUID.class), TaskStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                rs.getInt("attempt_count"), rs.getString("error_code"), rs.getString("error_message"),
                withText ? rs.getString("original_text") : null);
    }

    private record Joined(String id, String channel, Instant submittedAt, Instant completedAt,
                          boolean ready, boolean invalidated, Instant invalidatedAt, long hits,
                          UUID taskId, TaskStatus status, Instant createdAt, Instant startedAt,
                          Instant finishedAt, int attempts, String errorCode, String errorMessage, String text) {
        DocumentSummary summary() {
            return new DocumentSummary(id, channel, submittedAt, completedAt, ready, invalidated,
                    invalidatedAt, hits, taskId, status);
        }
        DocumentDetail detail() {
            return new DocumentDetail(id, channel, submittedAt, completedAt, ready, invalidated,
                    invalidatedAt, hits, taskId, status, text);
        }
        TaskView task() {
            TaskView.Error error = errorCode == null ? null : new TaskView.Error(errorCode, errorMessage);
            return new TaskView(taskId, id, status, createdAt, startedAt, finishedAt, attempts,
                    error, status == TaskStatus.SUCCEEDED ? detail() : null);
        }
    }

    private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
