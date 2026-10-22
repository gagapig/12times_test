package com.example.vectorsearch;

import com.example.vectorsearch.persistence.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate-process fixture, never packaged into the runnable application. */
public class FileDatabaseProbe {
    private static final UUID TASK = UUID.fromString("7ac1ebaa-e50c-4f22-9083-640e19156b49");
    private static final Instant TIME = Instant.parse("2026-10-21T12:00:00.123456Z");

    public static void main(String[] args) {
        try (var context = new SpringApplicationBuilder(VectorSearchApplication.class)
                .web(WebApplicationType.NONE).run("--spring.datasource.url=" + args[1])) {
            var jdbc = context.getBean(JdbcTemplate.class);
            if (args[0].equals("write")) {
                // Fixture writes validate persistence only; these are not business implementations.
                var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                tx.executeWithoutResult(status -> {
                    EntityManager em = org.springframework.orm.jpa.EntityManagerFactoryUtils
                            .getTransactionalEntityManager(context.getBean(EntityManagerFactory.class));
                    em.persist(new DocumentRecord("persisted", "原文😀\n", "中文渠道", TIME));
                    em.persist(new VectorTask(TASK, "persisted", TIME));
                    em.flush();
                    jdbc.update("UPDATE document_record SET vector_bytes=?, vector_version='charhash-v1', "
                            + "vector_ready=true, vector_completed_at=?, invalidated=true, invalidated_at=?, hit_count=7 "
                            + "WHERE document_id='persisted'", vector(), TIME, TIME);
                    jdbc.update("UPDATE vector_task SET status='SUCCEEDED', started_at=?, finished_at=?, "
                            + "attempt_count=2, recovery_count=1, last_recovered_at=? WHERE task_id=?", TIME, TIME, TIME, TASK);
                });
                System.out.println("PROBE_WRITE_OK");
            } else {
                var doc = context.getBean(DocumentRepository.class).findById("persisted").orElseThrow();
                var task = context.getBean(TaskRepository.class).findById(TASK).orElseThrow();
                require(doc.getOriginalText().equals("原文😀\n") && doc.getChannel().equals("中文渠道"), "Unicode CLOB/channel");
                require(doc.getSubmittedAt().equals(TIME) && doc.getVectorCompletedAt().equals(TIME), "timestamps");
                require(doc.isVectorReady() && doc.isInvalidated() && doc.getInvalidatedAt().equals(TIME), "flags");
                require(doc.getHitCount() == 7 && Arrays.equals(doc.getVectorBytes(), vector()), "bytes/counter");
                require(doc.getVectorVersion().equals("charhash-v1"), "version");
                require(task.getStatus() == TaskStatus.SUCCEEDED && task.getAttemptCount() == 2
                        && task.getRecoveryCount() == 1 && task.getLastRecoveredAt().equals(TIME), "task state");
                require(task.getCreatedAt().equals(TIME) && task.getStartedAt().equals(TIME)
                        && task.getFinishedAt().equals(TIME) && task.getErrorCode() == null, "task times");
                byte[] changedCopy = doc.getVectorBytes();
                changedCopy[0] ^= 1;
                require(Arrays.equals(doc.getVectorBytes(), vector()), "defensive copy");
                System.out.println("PROBE_READ_OK");
            }
        }
    }

    private static byte[] vector() {
        var bytes = new byte[1024];
        // Known byte payload only: P2 vectorization is deliberately absent.
        bytes[0] = 0x3f; bytes[1] = (byte) 0x80;
        return bytes;
    }

    private static void require(boolean condition, String field) {
        if (!condition) { throw new IllegalStateException("Persistence mismatch: " + field); }
    }
}
