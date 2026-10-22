package com.example.vectorsearch;

import com.example.vectorsearch.api.SubmitDocumentRequest;
import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.task.DocumentTasks;
import com.example.vectorsearch.task.TaskStore;
import com.example.vectorsearch.vector.EmbeddingPipeline;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Separate-process fixture: the parent kills this process only after RUNNING is durable. */
public class CrashRecoveryProbe {
    public static void main(String[] args) throws Exception {
        var builder = args[0].equals("start")
                ? new SpringApplicationBuilder(VectorSearchApplication.class, BlockedPipelineConfig.class)
                : new SpringApplicationBuilder(VectorSearchApplication.class);
        try (var context = builder.web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + args[1], "--vector.simulation-rounds=0",
                        "--task.dispatch-interval=100ms")) {
            TaskStore store = context.getBean(TaskStore.class);
            if (args[0].equals("start")) {
                var request = new SubmitDocumentRequest();
                request.setDocumentId("crash-running");
                request.setText("CRASH");
                context.getBean(DocumentTasks.class).submit(request);
                await(store, TaskStatus.RUNNING);
                System.out.println("CRASH_RUNNING_DURABLE");
                System.out.flush();
                Thread.sleep(Long.MAX_VALUE);
            } else {
                await(store, TaskStatus.SUCCEEDED);
                var task = store.taskForDocument("crash-running").orElseThrow();
                if (task.attemptCount() != 2 || !task.document().vectorReady()) {
                    throw new IllegalStateException("Crash recovery did not consume exactly one extra execution");
                }
                System.out.println("CRASH_RECOVERED_OK");
            }
        }
    }

    private static void await(TaskStore store, TaskStatus status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            var task = store.taskForDocument("crash-running");
            if (task.isPresent() && task.get().status() == status) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("Task did not reach " + status);
    }

    @TestConfiguration
    static class BlockedPipelineConfig {
        @Bean @Primary EmbeddingPipeline blockedPipeline() {
            return new EmbeddingPipeline(0) {
                @Override public EmbeddingResult embed(String text) {
                    if (text.equals("CRASH")) {
                        try { new java.util.concurrent.CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new java.util.concurrent.CancellationException("interrupted");
                        }
                    }
                    return super.embed(text);
                }
            };
        }
    }
}
