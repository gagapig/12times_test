package com.example.vectorsearch.task;

import com.example.vectorsearch.api.*;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.persistence.TaskStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DocumentTasks {
    private static final Pattern DOCUMENT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> LIST_KEYS = Set.of("page", "size", "channel", "invalidated", "vectorReady", "taskStatus");
    private final TaskStore store;
    private final TaskEngine engine;
    private final DatabaseRetry retry;
    private final TaskSettings settings;
    private final VisibilityGate gate;

    public DocumentTasks(TaskStore store, TaskEngine engine, DatabaseRetry retry,
                         TaskSettings settings, VisibilityGate gate) {
        this.store = store;
        this.engine = engine;
        this.retry = retry;
        this.settings = settings;
        this.gate = gate;
    }

    public SubmitResponse submit(SubmitDocumentRequest request) {
        engine.requireReady();
        UUID taskId = UUID.randomUUID();
        try {
            retry.run(() -> createUnderGate(request, taskId));
        } catch (StorageFailure failure) {
            if (failure.kind() == Kind.OUTCOME_UNKNOWN) {
                // The write must not be replayed. A fresh read may prove that this task committed.
                try {
                    if (store.task(taskId).filter(task -> task.documentId().equals(request.getDocumentId()))
                            .isPresent()) {
                        engine.signalDispatch();
                        return accepted(taskId, request.getDocumentId());
                    }
                } catch (StorageFailure lookupFailure) {
                    engine.degrade(lookupFailure);
                    throw unavailable(failure);
                }
                engine.degrade(failure);
                throw unavailable(failure);
            }
            if (failure.kind() == Kind.INTEGRITY) {
                // This mapping is reserved for a proven existing document, not any FK/CHECK error.
                try {
                    if (store.document(request.getDocumentId()).isPresent()) {
                        throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_EXISTS", "文档 ID 已存在");
                    }
                } catch (StorageFailure lookupFailure) {
                    engine.degrade(lookupFailure);
                    throw unavailable(lookupFailure);
                }
            }
            if (failure.kind() == Kind.UNAVAILABLE || failure.kind() == Kind.OUTCOME_UNKNOWN) engine.degrade(failure);
            throw unavailable(failure);
        }
        engine.signalDispatch();
        return accepted(taskId, request.getDocumentId());
    }

    private static SubmitResponse accepted(UUID taskId, String documentId) {
        return new SubmitResponse(taskId, documentId, TaskStatus.QUEUED, "/api/tasks/" + taskId);
    }

    private Void createUnderGate(SubmitDocumentRequest request, UUID taskId) {
        try {
            if (!gate.write().tryLock(settings.getGateWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENCY_TIMEOUT", "写操作等待超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENCY_TIMEOUT", "写操作被中断");
        }
        try {
            engine.requireReady();
            store.create(request.getDocumentId(), request.getText(), request.getChannel(), taskId,
                    Instant.now(), settings.getMaxPendingTasks());
            return null;
        } finally {
            gate.write().unlock();
        }
    }

    public TaskView task(String id) {
        UUID taskId = parseUuid(id);
        return read(() -> store.task(taskId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在")));
    }

    public TaskView taskForDocument(String documentId) {
        requireDocumentId(documentId);
        return read(() -> store.taskForDocument(documentId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "文档不存在")));
    }

    public DocumentDetail document(String documentId) {
        requireDocumentId(documentId);
        return read(() -> store.document(documentId).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "文档不存在")));
    }

    public DocumentSummary invalidate(String documentId) {
        requireDocumentId(documentId);
        engine.requireReady();
        try {
            return retry.run(() -> invalidateUnderGate(documentId));
        } catch (StorageFailure failure) {
            if (failure.kind() == Kind.OUTCOME_UNKNOWN) {
                try {
                    var persisted = store.summary(documentId);
                    if (persisted.isPresent() && persisted.get().invalidated()) return persisted.get();
                } catch (StorageFailure lookupFailure) {
                    engine.degrade(lookupFailure);
                    throw unavailable(failure);
                }
            }
            if (failure.kind() == Kind.UNAVAILABLE || failure.kind() == Kind.OUTCOME_UNKNOWN) engine.degrade(failure);
            throw unavailable(failure);
        }
    }

    private DocumentSummary invalidateUnderGate(String documentId) {
        try {
            if (!gate.write().tryLock(settings.getGateWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENCY_TIMEOUT", "写操作等待超时");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CONCURRENCY_TIMEOUT", "写操作被中断");
        }
        try {
            engine.requireReady();
            return store.invalidate(documentId, Instant.now());
        } finally {
            gate.write().unlock();
        }
    }

    public DocumentPage list(Map<String, String[]> parameters) {
        for (var entry : parameters.entrySet()) {
            if (!LIST_KEYS.contains(entry.getKey()) || entry.getValue().length != 1) {
                throw invalid("未知或重复的列表参数");
            }
        }
        int page = integer(parameters, "page", 0, 0, Integer.MAX_VALUE);
        int size = integer(parameters, "size", 20, 1, 100);
        String channel = single(parameters, "channel");
        if (channel != null && !new com.example.vectorsearch.validation.ChannelValidator()
                .isValid(channel, null)) {
            throw invalid("渠道过滤值不合法");
        }
        Boolean invalidated = bool(parameters, "invalidated");
        Boolean vectorReady = bool(parameters, "vectorReady");
        String statusString = single(parameters, "taskStatus");
        TaskStatus status = null;
        if (statusString != null) {
            try { status = TaskStatus.valueOf(statusString); }
            catch (IllegalArgumentException failure) { throw invalid("任务状态过滤值不合法"); }
        }
        TaskStatus filter = status;
        return read(() -> store.list(page, size, channel, invalidated, vectorReady, filter));
    }

    private <T> T read(Supplier<T> query) {
        engine.requireReady();
        try { return retry.run(query); }
        catch (StorageFailure failure) {
            if (failure.kind() == Kind.UNAVAILABLE || failure.kind() == Kind.OUTCOME_UNKNOWN) engine.degrade(failure);
            throw unavailable(failure);
        }
    }

    private static ApiException unavailable(StorageFailure failure) {
        if (failure.kind() == Kind.INTEGRITY || failure.kind() == Kind.OTHER) {
            return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "数据操作失败");
        }
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                failure.kind() == Kind.OUTCOME_UNKNOWN ? "STORAGE_OUTCOME_UNKNOWN" : "STORAGE_UNAVAILABLE",
                failure.kind() == Kind.OUTCOME_UNKNOWN ? "提交结果待核对，请按文档 ID 查询" : "数据库暂不可用");
    }

    private static String single(Map<String, String[]> args, String key) {
        return args.containsKey(key) ? args.get(key)[0] : null;
    }

    private static int integer(Map<String, String[]> args, String key, int fallback, int min, int max) {
        String value = single(args, key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) { }
        throw invalid(key + " 超出范围");
    }

    private static Boolean bool(Map<String, String[]> args, String key) {
        String value = single(args, key);
        if (value == null) return null;
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw invalid(key + " 必须为 true 或 false");
    }

    private static UUID parseUuid(String value) {
        try {
            UUID result = UUID.fromString(value);
            if (result.toString().equalsIgnoreCase(value)) return result;
        } catch (IllegalArgumentException ignored) { }
        throw invalid("taskId 须为标准 UUID");
    }

    private static void requireDocumentId(String value) {
        if (!DOCUMENT_ID.matcher(value).matches()) throw invalid("documentId 格式不合法");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
