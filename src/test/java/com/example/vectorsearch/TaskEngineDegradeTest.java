package com.example.vectorsearch;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Outcome;
import com.example.vectorsearch.task.*;
import com.example.vectorsearch.vector.EmbeddingPipeline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskEngineDegradeTest {
    @Test
    void databaseOutageDegradesServiceWithoutExitingAndProbeRestoresScheduling() throws Exception {
        TaskStore store = mock(TaskStore.class);
        when(store.queuedAfter(nullable(TaskStore.Cursor.class), anyInt())).thenReturn(List.of());
        when(store.probe()).thenReturn(true);
        var settings = new TaskSettings();
        settings.setWorkerCount(1);
        settings.setQueueCapacity(1);
        settings.setDispatchInterval(Duration.ofMillis(50));
        settings.setShutdownGrace(Duration.ofSeconds(1));
        var engine = new TaskEngine(store, new EmbeddingPipeline(0), settings,
                new VisibilityGate(), new DatabaseRetry(settings));
        engine.start();
        try {
            engine.requireReady();
            engine.degrade(new StorageFailure(Kind.UNAVAILABLE, Outcome.NOT_STARTED,
                    new IllegalStateException("database offline")));
            assertThatThrownBy(engine::requireReady).isInstanceOf(ApiException.class);
            assertThat(engine.isRunning()).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                try { engine.requireReady(); ready = true; break; }
                catch (ApiException waiting) { Thread.sleep(50); }
            }
            assertThat(ready).isTrue();
            verify(store, atLeastOnce()).probe();
        } finally {
            engine.stop();
        }
    }
}
