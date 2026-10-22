package com.example.vectorsearch;

import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.task.TaskEngine;
import com.example.vectorsearch.task.TaskSettings;
import com.example.vectorsearch.task.TaskStore;
import com.example.vectorsearch.vector.VectorCodec;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"task.enabled=false", "task.worker-count=1", "task.dispatch-interval=100ms"})
class TaskRecoveryTest {
    private static final String DB = Path.of("target", "recovery-tests", UUID.randomUUID().toString(), "store")
            .toAbsolutePath().toString();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + DB + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
    }

    @Autowired TaskStore store;
    @Autowired TaskEngine engine;
    @Autowired TaskSettings settings;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startupRecoversQueuedAndInterruptedClaimsWithinThreeExecutionLimit() throws Exception {
        UUID queued = seed("queued");
        UUID interrupted = seed("interrupted");
        UUID exhausted = seed("exhausted");
        UUID completed = seed("completed");
        UUID failed = seed("failed");
        jdbc.update("UPDATE vector_task SET status='RUNNING', attempt_count=1, started_at=CURRENT_TIMESTAMP "
                + "WHERE task_id=?", interrupted);
        jdbc.update("UPDATE vector_task SET status='RUNNING', attempt_count=3, started_at=CURRENT_TIMESTAMP "
                + "WHERE task_id=?", exhausted);
        jdbc.update("UPDATE vector_task SET status='SUCCEEDED', attempt_count=1, finished_at=CURRENT_TIMESTAMP "
                + "WHERE task_id=?", completed);
        jdbc.update("UPDATE document_record SET vector_ready=true, vector_bytes=?, vector_version=?, "
                        + "vector_completed_at=CURRENT_TIMESTAMP WHERE document_id=(SELECT document_id "
                        + "FROM vector_task WHERE task_id=?)",
                VectorCodec.encode(new float[256]), VectorCodec.VERSION, completed);
        jdbc.update("UPDATE vector_task SET status='FAILED', attempt_count=1, finished_at=CURRENT_TIMESTAMP, "
                + "error_code='VECTORIZE_FAILED', error_message='previous failure' WHERE task_id=?", failed);

        settings.setEnabled(true);
        try {
            engine.start();
            await(queued, TaskStatus.SUCCEEDED);
            await(interrupted, TaskStatus.SUCCEEDED);
            await(exhausted, TaskStatus.FAILED);
            assertThat(store.task(completed).orElseThrow().status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(store.task(failed).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);

            assertThat(store.task(queued).orElseThrow().attemptCount()).isEqualTo(1);
            assertThat(store.task(interrupted).orElseThrow().attemptCount()).isEqualTo(2);
            assertThat(store.task(exhausted).orElseThrow().attemptCount()).isEqualTo(3);
            assertThat(store.task(exhausted).orElseThrow().error().code())
                    .isEqualTo("RECOVERY_LIMIT_EXCEEDED");
            assertThat(jdbc.queryForObject("SELECT recovery_count FROM vector_task WHERE task_id=?", Integer.class,
                    interrupted)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT recovery_count FROM vector_task WHERE task_id=?", Integer.class,
                    completed)).isZero();
            assertThat(jdbc.queryForObject("SELECT recovery_count FROM vector_task WHERE task_id=?", Integer.class,
                    failed)).isZero();
        } finally {
            engine.stop();
            settings.setEnabled(false);
        }
    }

    private UUID seed(String label) {
        UUID task = UUID.randomUUID();
        store.create("recovery-" + label + "-" + UUID.randomUUID(), "recover me", "default",
                task, Instant.now(), 10);
        return task;
    }

    private void await(UUID task, TaskStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (store.task(task).orElseThrow().status() == expected) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + task + " did not reach " + expected);
    }
}
