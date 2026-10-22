package com.example.vectorsearch;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.api.SubmitDocumentRequest;
import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.task.DocumentTasks;
import com.example.vectorsearch.task.TaskEngine;
import com.example.vectorsearch.task.TaskStore;
import com.example.vectorsearch.vector.EmbeddingPipeline;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TaskWorkerExitTest.ControlledConfig.class)
class TaskWorkerExitTest {
    private static final String DB = Path.of("target", "worker-exit-tests", UUID.randomUUID().toString(), "store")
            .toAbsolutePath().toString();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + DB + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
        registry.add("task.worker-count", () -> 1);
        registry.add("task.dispatch-interval", () -> "100ms");
        registry.add("task.shutdown-grace", () -> "2s");
    }

    @Autowired TaskEngine engine;
    @Autowired TaskStore store;
    @Autowired DocumentTasks tasks;
    @Autowired ControlledPipeline pipeline;

    @Test
    void unexpectedWorkerExitMustStopAcceptingUntilProcessRestart() throws Exception {
        String first = "worker-exit-" + UUID.randomUUID();
        String second = "worker-after-exit-" + UUID.randomUUID();
        try {
            UUID taskId = tasks.submit(request(first)).taskId();
            assertThat(pipeline.entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.task(taskId).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);

            Thread worker = worker();
            worker.interrupt(); // Unexpected while READY, not an application shutdown.
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(store.task(taskId).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
            assertThatThrownBy(engine::requireReady).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo("SERVICE_NOT_READY"));
            assertThatThrownBy(() -> tasks.submit(request(second)))
                    .isInstanceOfSatisfying(ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("SERVICE_NOT_READY"));
            assertThat(store.document(second)).isEmpty();
        } finally {
            pipeline.release();
            engine.stop();
        }
    }

    private static SubmitDocumentRequest request(String id) {
        var request = new SubmitDocumentRequest();
        request.setDocumentId(id);
        request.setText("interrupted worker");
        return request;
    }

    @SuppressWarnings("unchecked")
    private Thread worker() throws ReflectiveOperationException {
        Field field = TaskEngine.class.getDeclaredField("workers");
        field.setAccessible(true);
        return ((List<Thread>) field.get(engine)).get(0);
    }

    @TestConfiguration
    static class ControlledConfig {
        @Bean @Primary ControlledPipeline controlledPipeline() { return new ControlledPipeline(); }
    }

    static class ControlledPipeline extends EmbeddingPipeline {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        ControlledPipeline() { super(0); }
        void release() { resume.countDown(); }

        @Override
        public EmbeddingResult embed(String text) {
            entered.countDown();
            try { resume.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("worker unexpectedly interrupted");
            }
            return super.embed(text);
        }
    }
}
