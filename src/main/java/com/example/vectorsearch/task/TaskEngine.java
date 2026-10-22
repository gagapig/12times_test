package com.example.vectorsearch.task;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.vector.EmbeddingPipeline;
import com.example.vectorsearch.vector.VectorCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** One bounded scheduler and a fixed number of long-lived worker loops. */
@Component
public class TaskEngine implements SmartLifecycle {
    private enum State { STARTING, READY, DEGRADED, RECOVERING, STOPPING }
    private static final Logger LOG = LoggerFactory.getLogger(TaskEngine.class);
    private final TaskStore store;
    private final EmbeddingPipeline embedding;
    private final TaskSettings settings;
    private final VisibilityGate gate;
    private final DatabaseRetry retry;
    private final ArrayBlockingQueue<UUID> queue;
    private final Object scheduleMonitor = new Object();
    private final HashSet<UUID> scheduledIds = new HashSet<>();
    private final Object stateMonitor = new Object();
    private final Semaphore wake = new Semaphore(0);
    private final AtomicBoolean wakePending = new AtomicBoolean();
    private final AtomicInteger activeClaims = new AtomicInteger();
    private final List<Thread> workers = new ArrayList<>();
    private volatile State state = State.STARTING;
    private volatile Thread dispatcher;
    private volatile boolean running;

    public TaskEngine(TaskStore store, EmbeddingPipeline embedding, TaskSettings settings,
                      VisibilityGate gate, DatabaseRetry retry) {
        this.store = store;
        this.embedding = embedding;
        this.settings = settings;
        this.gate = gate;
        this.retry = retry;
        settings.validate();
        this.queue = new ArrayBlockingQueue<>(settings.getQueueCapacity());
    }

    @Override
    public boolean isAutoStartup() { return settings.isEnabled(); }

    @Override
    public synchronized void start() {
        if (running || !settings.isEnabled()) return;
        // Nothing is serving or consuming yet; a failed recovery aborts application startup.
        retry.run(() -> { store.recover(settings.getMaxAttempts()); return null; });
        running = true;
        state = State.READY;
        for (int i = 0; i < settings.getWorkerCount(); i++) {
            Thread worker = new Thread(this::workerLoop, "vector-worker-" + i);
            workers.add(worker);
            worker.start();
        }
        dispatcher = new Thread(this::dispatchLoop, "vector-dispatcher");
        dispatcher.start();
        signalDispatch();
    }

    @Override
    public boolean isRunning() { return running; }

    @Override
    public void stop(Runnable callback) {
        try { stop(); } finally { callback.run(); }
    }

    @Override
    public synchronized void stop() {
        if (!running) return;
        setState(State.STOPPING);
        signalDispatch();
        long deadline = System.nanoTime() + settings.getShutdownGrace().toNanos();
        boolean interrupted = false;
        for (Thread thread : allThreads()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))); }
            catch (InterruptedException ex) { interrupted = true; break; }
        }
        for (Thread thread : allThreads()) {
            if (thread.isAlive()) thread.interrupt();
        }
        for (Thread thread : allThreads()) {
            try { thread.join(1000); }
            catch (InterruptedException ex) { interrupted = true; break; }
        }
        boolean stopped = allThreads().stream().noneMatch(Thread::isAlive);
        if (stopped) {
            synchronized (scheduleMonitor) {
                queue.clear();
                scheduledIds.clear();
            }
        } else {
            LOG.error("Task threads did not stop; scheduling state was kept intact");
        }
        if (interrupted) Thread.currentThread().interrupt();
        running = false;
    }

    private List<Thread> allThreads() {
        List<Thread> result = new ArrayList<>(workers);
        if (dispatcher != null) result.add(dispatcher);
        return result;
    }

    public void requireReady() {
        if (state != State.READY) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_NOT_READY", "任务服务暂不可用");
        }
    }

    public void signalDispatch() {
        if (wakePending.compareAndSet(false, true)) wake.release();
    }

    public int scheduledCount() {
        synchronized (scheduleMonitor) { return scheduledIds.size(); }
    }

    public void degrade(StorageFailure failure) {
        if (state != State.STOPPING && state != State.DEGRADED) {
            LOG.error("Task service degraded after storage failure: kind={}, outcome={}",
                    failure.kind(), failure.outcome());
            setState(State.DEGRADED);
            signalDispatch();
        }
    }

    private void setState(State next) {
        synchronized (stateMonitor) {
            state = next;
            stateMonitor.notifyAll();
        }
    }

    private boolean awaitClaimPermission() throws InterruptedException {
        synchronized (stateMonitor) {
            while (state != State.READY && state != State.STOPPING) stateMonitor.wait();
            return state == State.READY;
        }
    }

    private boolean awaitDatabaseForActive() throws InterruptedException {
        synchronized (stateMonitor) {
            while (state == State.DEGRADED || state == State.STARTING) stateMonitor.wait();
            return state != State.STOPPING;
        }
    }

    private void maybeFinishRecovery() {
        synchronized (stateMonitor) {
            if (state == State.RECOVERING && activeClaims.get() == 0) {
                state = State.READY;
                stateMonitor.notifyAll();
                signalDispatch();
            }
        }
    }

    private void dispatchLoop() {
        int probes = 0;
        while (state != State.STOPPING) {
            try {
                if (state == State.READY) {
                    probes = 0;
                    scanQueued();
                    wake.tryAcquire(settings.getDispatchInterval().toMillis(), TimeUnit.MILLISECONDS);
                    wakePending.set(false);
                } else if (state == State.DEGRADED && probes < 12) {
                    TimeUnit.SECONDS.sleep(5);
                    if (state != State.DEGRADED) continue;
                    probes++;
                    try {
                        if (store.probe()) {
                            LOG.info("Database probe succeeded after {} attempts", probes);
                            setState(State.RECOVERING);
                            maybeFinishRecovery();
                        }
                    } catch (RuntimeException failure) {
                        LOG.warn("Database probe {} failed: {}", probes, failure.getClass().getSimpleName());
                    }
                } else {
                    synchronized (stateMonitor) { stateMonitor.wait(1000); }
                }
            } catch (StorageFailure failure) {
                if (failure.kind() == Kind.UNAVAILABLE || failure.kind() == Kind.OUTCOME_UNKNOWN) degrade(failure);
                else LOG.warn("Dispatch scan will retry later: {}", failure.kind());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException failure) {
                LOG.error("Dispatch scan failed", failure);
            }
        }
    }

    private void scanQueued() {
        TaskStore.Cursor cursor = null;
        for (;;) {
            TaskStore.Cursor after = cursor;
            List<TaskStore.Cursor> batch = retry.run(() -> store.queuedAfter(after, settings.getDispatchBatchSize()));
            if (batch.isEmpty()) return;
            for (TaskStore.Cursor entry : batch) {
                cursor = entry; // Even duplicates advance the keyset, preventing starvation.
                if (!offerScheduled(entry.taskId())) return;
            }
            if (batch.size() < settings.getDispatchBatchSize()) return;
        }
    }

    private boolean offerScheduled(UUID taskId) {
        synchronized (scheduleMonitor) {
            if (!scheduledIds.add(taskId)) return true;
            try {
                if (queue.offer(taskId)) return true;
                scheduledIds.remove(taskId);
                return false;
            } catch (RuntimeException failure) {
                scheduledIds.remove(taskId);
                throw failure;
            }
        }
    }

    private void workerLoop() {
        try {
            while (state != State.STOPPING) {
                try {
                    if (!awaitClaimPermission()) break;
                    UUID taskId = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (taskId == null) continue;
                    try { execute(taskId); }
                    finally {
                        synchronized (scheduleMonitor) { scheduledIds.remove(taskId); }
                        signalDispatch();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    LOG.error("Worker loop failed; next task can still run", failure);
                }
            }
        } finally {
            if (state != State.STOPPING) {
                LOG.error("Worker exited unexpectedly; restart is required to recover RUNNING tasks");
                setState(State.STOPPING);
                signalDispatch();
            }
        }
    }

    private void execute(UUID taskId) throws InterruptedException {
        activeClaims.incrementAndGet();
        try {
            TaskStore.Claim claim;
            try {
                claim = retry.run(() -> store.claim(taskId, settings.getMaxAttempts()));
            } catch (StorageFailure failure) {
                if (failure.kind() == Kind.TRANSIENT_ROLLED_BACK) {
                    LOG.warn("Claim conflict exhausted for task {}; scheduler will retry", taskId);
                    return;
                }
                degrade(failure);
                if (!awaitDatabaseForActive()) return;
                // The claim may have committed before the connection failed; reuse its attempt.
                claim = awaitStorageRead(() -> store.runningClaim(taskId).orElse(null));
                if (claim == null) return; // QUEUED is redispatched; terminal tasks remain terminal.
            }
            if (claim == null) return;
            finishClaim(claim);
        } finally {
            activeClaims.decrementAndGet();
            maybeFinishRecovery();
        }
    }

    private void finishClaim(TaskStore.Claim claim) throws InterruptedException {
        byte[] vector;
        try {
            vector = VectorCodec.encode(embedding.embed(claim.text()).vector());
        } catch (CancellationException cancelled) {
            if (state == State.STOPPING || Thread.currentThread().isInterrupted()) return;
            terminalFailure(claim, "VECTORIZE_FAILED", "向量化被取消");
            return;
        } catch (IllegalArgumentException badText) {
            terminalFailure(claim, "TEXT_DATA_INVALID", "文本数据不合法");
            return;
        } catch (RuntimeException unexpected) {
            LOG.error("Vectorization failed for task {}", claim.taskId(), unexpected);
            terminalFailure(claim, "VECTORIZE_FAILED", "向量化执行失败");
            return;
        }
        for (;;) {
            if (state == State.STOPPING) return;
            try {
                retry.run(() -> publishUnderGate(claim, vector));
                return;
            } catch (StorageFailure failure) {
                degrade(failure);
                if (!awaitDatabaseForActive()) return;
                var persisted = awaitStorageRead(() -> store.state(claim.taskId()).orElse(null));
                if (state == State.STOPPING) return;
                if (persisted != null && persisted.status() == TaskStatus.SUCCEEDED
                        && persisted.attemptCount() == claim.attemptCount() && persisted.vectorReady()) return;
                if (persisted == null || persisted.status() != TaskStatus.RUNNING
                        || persisted.attemptCount() != claim.attemptCount()) {
                    LOG.error("Cannot safely republish task {} after storage failure", claim.taskId());
                    degrade(failure);
                    return;
                }
            }
        }
    }

    private Void publishUnderGate(TaskStore.Claim claim, byte[] vector) {
        try {
            gate.write().lockInterruptibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Publication interrupted during shutdown");
        }
        try {
            store.publish(claim, vector, VectorCodec.VERSION, Instant.now());
            return null;
        } finally {
            gate.write().unlock();
        }
    }

    private void terminalFailure(TaskStore.Claim claim, String code, String reason) throws InterruptedException {
        for (;;) {
            if (state == State.STOPPING) return;
            try {
                retry.run(() -> { store.fail(claim, code, reason, Instant.now()); return null; });
                return;
            } catch (StorageFailure failure) {
                degrade(failure);
                if (!awaitDatabaseForActive()) return;
                var persisted = awaitStorageRead(() -> store.state(claim.taskId()).orElse(null));
                if (state == State.STOPPING) return;
                if (persisted != null && persisted.status() == TaskStatus.FAILED
                        && persisted.attemptCount() == claim.attemptCount()) return;
                if (persisted == null || persisted.status() != TaskStatus.RUNNING
                        || persisted.attemptCount() != claim.attemptCount()) {
                    LOG.error("Cannot safely record failure for task {}", claim.taskId());
                    degrade(failure);
                    return;
                }
            }
        }
    }

    /** Keep the owning worker and its local result until a fresh read resolves a failed write. */
    private <T> T awaitStorageRead(java.util.function.Supplier<T> read) throws InterruptedException {
        for (;;) {
            if (state == State.STOPPING) return null;
            try { return retry.run(read); }
            catch (StorageFailure failure) {
                degrade(failure);
                if (!awaitDatabaseForActive()) return null;
            }
        }
    }
}
