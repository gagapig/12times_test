package com.example.vectorsearch.search;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.api.SearchRequest;
import com.example.vectorsearch.api.SearchResponse;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.task.*;
import com.example.vectorsearch.vector.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Exact single-instance search. The read lock covers all candidate reads and the count commit. */
@Service
public class SearchService {
    private final TaskStore store;
    private final EmbeddingPipeline pipeline;
    private final VisibilityGate gate;
    private final TaskEngine engine;
    private final TaskSettings taskSettings;
    private final SearchSettings settings;
    private final Semaphore permits;

    public SearchService(TaskStore store, EmbeddingPipeline pipeline, VisibilityGate gate,
                         TaskEngine engine, TaskSettings taskSettings, SearchSettings settings) {
        this.store = store;
        this.pipeline = pipeline;
        this.gate = gate;
        this.engine = engine;
        this.taskSettings = taskSettings;
        this.settings = settings;
        settings.validate();
        this.permits = new Semaphore(settings.getMaxConcurrent(), true);
    }

    public SearchResponse search(SearchRequest request) {
        engine.requireReady();
        if (!permits.tryAcquire()) throw unavailable("SEARCH_BUSY", "检索并发已达上限");
        long deadline = System.nanoTime() + settings.getBudget().toNanos();
        try {
            Runnable checkpoint = () -> check(deadline);
            float[] query;
            try { query = pipeline.embed(request.getText(), checkpoint).vector(); }
            catch (CancellationException cancelled) { throw unavailable("SEARCH_CANCELLED", "检索已取消"); }
            checkpoint.run();
            for (int attempt = 1; ; attempt++) {
                try {
                    return searchOnce(request, query, deadline, checkpoint);
                } catch (StorageFailure failure) {
                    if (failure.kind() == Kind.TRANSIENT_ROLLED_BACK
                            && attempt < taskSettings.getMaxDbAttempts()) {
                        pauseBeforeRetry(attempt, deadline);
                        continue;
                    }
                    if (failure.kind() == Kind.UNAVAILABLE) engine.degrade(failure);
                    if (failure.kind() == Kind.OUTCOME_UNKNOWN) {
                        throw unavailable("STORAGE_OUTCOME_UNKNOWN", "检索计数提交结果待核对");
                    }
                    if (failure.kind() == Kind.UNAVAILABLE || failure.kind() == Kind.TRANSIENT_ROLLED_BACK) {
                        throw unavailable("STORAGE_UNAVAILABLE", "数据库暂不可用");
                    }
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "检索数据操作失败");
                }
            }
        } finally {
            permits.release();
        }
    }

    private SearchResponse searchOnce(SearchRequest request, float[] query, long deadline,
                                      Runnable checkpoint) {
        boolean locked = false;
        try {
            checkpoint.run();
            long remaining = deadline - System.nanoTime();
            long wait = Math.min(settings.getGateWait().toNanos(), remaining);
            try { locked = gate.read().tryLock(Math.max(0, wait), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable("SEARCH_CANCELLED", "检索已取消");
            }
            if (!locked) {
                checkpoint.run();
                throw unavailable("CONCURRENCY_TIMEOUT", "检索等待锁超时");
            }
            checkpoint.run();

            TopKSelector top = new TopKSelector(request.getTopK());
            String after = null;
            int seen = 0;
            for (;;) {
                checkpoint.run();
                List<TaskStore.Candidate> batch = store.candidatesAfter(after, request.getChannel(),
                        settings.getCandidateBatchSize(), dbSeconds(deadline));
                checkpoint.run();
                for (TaskStore.Candidate candidate : batch) {
                    if ((seen++ & 63) == 0) checkpoint.run();
                    float[] vector;
                    try { vector = VectorCodec.decode(candidate.vectorBytes(), candidate.vectorVersion()); }
                    catch (IllegalArgumentException damaged) {
                        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "VECTOR_DATA_INVALID", "已存向量格式损坏或版本不受支持");
                    }
                    top.offer(candidate.documentId(), Similarity.cosine(query, vector));
                    after = candidate.documentId();
                }
                checkpoint.run();
                if (batch.size() < settings.getCandidateBatchSize()) break;
            }

            List<TopKSelector.Hit> hits = top.sorted();
            if (hits.isEmpty()) return new SearchResponse(request.getTopK(), 0, List.of());
            var details = store.countAndLoad(hits, checkpoint, dbSeconds(deadline));
            // Count committed. Do not turn a late timeout into a claim that the transaction rolled back.
            List<SearchResponse.Result> results = new ArrayList<>(hits.size());
            for (TopKSelector.Hit hit : hits) {
                results.add(new SearchResponse.Result(details.get(hit.documentId()), hit.score()));
            }
            return new SearchResponse(request.getTopK(), results.size(), List.copyOf(results));
        } finally {
            if (locked) gate.read().unlock();
        }
    }

    private void pauseBeforeRetry(int attempt, long deadline) {
        check(deadline);
        long delay = Math.min(50L * attempt, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        try { Thread.sleep(Math.max(1, delay)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("SEARCH_CANCELLED", "检索已取消");
        }
        check(deadline);
    }

    private int dbSeconds(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining < TimeUnit.SECONDS.toNanos(1)) throw unavailable("SEARCH_TIMEOUT", "检索整体超时");
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toSeconds(remaining)));
    }

    private void check(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw unavailable("SEARCH_CANCELLED", "检索已取消");
        if (System.nanoTime() >= deadline) throw unavailable("SEARCH_TIMEOUT", "检索整体超时");
        engine.requireReady();
    }

    private static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
