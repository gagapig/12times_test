package com.example.vectorsearch;

import com.example.vectorsearch.api.SearchRequest;
import com.example.vectorsearch.api.SubmitDocumentRequest;
import com.example.vectorsearch.persistence.DocumentRepository;
import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.search.SearchService;
import com.example.vectorsearch.task.DocumentTasks;
import com.example.vectorsearch.task.TaskEngine;
import com.example.vectorsearch.task.TaskStore;
import com.example.vectorsearch.vector.VectorCodec;
import com.example.vectorsearch.vector.Vectorizer;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate JVM fixture for business persistence and startup recovery. */
public class BusinessRestartProbe {
    private static final UUID QUEUED = UUID.fromString("de1d7baa-ebc8-4b29-9ea1-a47422200101");
    private static final UUID RUNNING = UUID.fromString("de1d7baa-ebc8-4b29-9ea1-a47422200102");

    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(VectorSearchApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + args[1], "--vector.simulation-rounds=0",
                        "--task.dispatch-interval=100ms")) {
            var tasks = context.getBean(DocumentTasks.class);
            var store = context.getBean(TaskStore.class);
            if (args[0].equals("write")) {
                var request = new SubmitDocumentRequest();
                request.setDocumentId("business-persisted");
                request.setText("Java 后端");
                tasks.submit(request);
                await(store, "business-persisted");

                var search = new SearchRequest();
                search.setText("Java 后端");
                search.setTopK(1);
                var result = context.getBean(SearchService.class).search(search);
                require(result.returnedCount() == 1
                        && result.results().get(0).document().documentId().equals("business-persisted"), "search");
                tasks.invalidate("business-persisted");

                context.getBean(TaskEngine.class).stop();
                store.create("pending-queued", "", "default", QUEUED, Instant.now(), 100);
                store.create("pending-running", "", "default", RUNNING, Instant.now(), 100);
                context.getBean(JdbcTemplate.class).update("UPDATE vector_task SET status='RUNNING', "
                        + "attempt_count=1, started_at=CURRENT_TIMESTAMP WHERE task_id=?", RUNNING);
                System.out.println("BUSINESS_WRITE_OK");
            } else {
                await(store, "pending-queued");
                await(store, "pending-running");
                var persisted = tasks.document("business-persisted");
                require(persisted.text().equals("Java 后端") && persisted.invalidated()
                        && persisted.vectorReady() && persisted.hitCount() == 1, "persisted document");
                var bytes = context.getBean(DocumentRepository.class).findById("business-persisted")
                        .orElseThrow().getVectorBytes();
                require(Arrays.equals(bytes, VectorCodec.encode(new Vectorizer().vectorize("Java 后端"))),
                        "stored vector bytes");
                require(store.task(QUEUED).orElseThrow().attemptCount() == 1, "queued recovery");
                require(store.task(RUNNING).orElseThrow().attemptCount() == 2, "running recovery");
                var search = new SearchRequest();
                search.setText("Java 后端");
                search.setTopK(3);
                var result = context.getBean(SearchService.class).search(search);
                require(result.results().stream().noneMatch(hit ->
                        hit.document().documentId().equals("business-persisted")), "invalidated exclusion");
                System.out.println("BUSINESS_RESTART_OK");
            }
        }
    }

    private static void await(TaskStore store, String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (store.taskForDocument(id).orElseThrow().status() == TaskStatus.SUCCEEDED) return;
            Thread.sleep(25);
        }
        throw new IllegalStateException("Task not complete: " + id);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Business restart mismatch: " + label);
    }
}
