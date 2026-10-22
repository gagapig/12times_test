package com.example.vectorsearch;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.api.SearchRequest;
import com.example.vectorsearch.search.SearchService;
import com.example.vectorsearch.search.SearchSettings;
import com.example.vectorsearch.task.*;
import com.example.vectorsearch.vector.EmbeddingPipeline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchResourceReleaseTest {
    @Test
    void interruptionDuringCandidateReadReleasesReadLockAndSearchPermit() throws Exception {
        TaskStore store = mock(TaskStore.class);
        VisibilityGate gate = new VisibilityGate();
        SearchService service = service(store, gate, Duration.ofSeconds(10));
        CountDownLatch entered = new CountDownLatch(1);
        when(store.candidatesAfter(nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_CANCELLED", "cancelled");
                    }
                    return List.of();
                });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try { service.search(request()); }
            catch (Throwable error) { failure.set(error); }
        }, "search-cancellation-test");
        request.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.write().tryLock()).isFalse();
            assertThatThrownBy(() -> service.search(request()))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("SEARCH_BUSY"));
        } finally {
            request.interrupt();
            request.join(5000);
        }
        assertThat(request.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo("SEARCH_CANCELLED"));
        assertWriteLockFree(gate);

        doReturn(List.of()).when(store)
                .candidatesAfter(nullable(String.class), nullable(String.class), anyInt(), anyInt());
        assertThat(service.search(request()).returnedCount()).isZero();
    }

    @Test
    void wholeSearchTimeoutAlsoReleasesReadLockAndPermit() {
        TaskStore store = mock(TaskStore.class);
        VisibilityGate gate = new VisibilityGate();
        SearchService service = service(store, gate, Duration.ofSeconds(2));
        when(store.candidatesAfter(nullable(String.class), nullable(String.class), anyInt(), anyInt()))
                .thenAnswer(call -> { Thread.sleep(2200); return List.of(); });

        assertThatThrownBy(() -> service.search(request()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SEARCH_TIMEOUT"));
        assertWriteLockFree(gate);
        doReturn(List.of()).when(store)
                .candidatesAfter(nullable(String.class), nullable(String.class), anyInt(), anyInt());
        assertThat(service.search(request()).returnedCount()).isZero();
    }

    private static SearchService service(TaskStore store, VisibilityGate gate, Duration budget) {
        var settings = new SearchSettings();
        settings.setMaxConcurrent(1);
        settings.setBudget(budget);
        return new SearchService(store, new EmbeddingPipeline(0), gate, mock(TaskEngine.class),
                new TaskSettings(), settings);
    }

    private static SearchRequest request() {
        var request = new SearchRequest();
        request.setText("");
        request.setTopK(1);
        return request;
    }

    private static void assertWriteLockFree(VisibilityGate gate) {
        assertThat(gate.write().tryLock()).isTrue();
        gate.write().unlock();
    }
}
