package com.example.vectorsearch;

import com.example.vectorsearch.persistence.*;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext
class PersistenceTest {
    private static final String DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "store")
            .toAbsolutePath().toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:file:" + DB + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
        properties.add("task.enabled", () -> false);
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DocumentRepository documents;
    @Autowired TaskRepository tasks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void jpaRoundTripsEmptyClobChineseChannelUuidAndUtcTimestamp() {
        var id = "empty-" + UUID.randomUUID();
        var taskId = UUID.randomUUID();
        var time = Instant.parse("2026-10-21T12:00:00.123456Z");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new DocumentRecord(id, "", "中文知识库", time));
            em.persist(new VectorTask(taskId, id, time));
            em.flush();
        });
        var doc = documents.findById(id).orElseThrow();
        var task = tasks.findByDocumentId(id).orElseThrow();
        assertThat(doc.getOriginalText()).isEmpty();
        assertThat(doc.getChannel()).isEqualTo("中文知识库");
        assertThat(doc.getSubmittedAt()).isEqualTo(time);
        assertThat(doc.isVectorReady()).isFalse();
        assertThat(doc.getVectorBytes()).isNull();
        assertThat(doc.getHitCount()).isZero();
        assertThat(task.getTaskId()).isEqualTo(taskId);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.getAttemptCount()).isZero();
    }

    @Test
    void uniqueDocumentAndTaskForeignKeyPreventPartialOrDuplicateRecords() {
        var id = "unique-" + UUID.randomUUID();
        insert(id);
        assertThatThrownBy(() -> insert(id)).isInstanceOf(RuntimeException.class);
        var orphan = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new VectorTask(orphan, "missing", Instant.now()));
            em.flush();
        })).isInstanceOf(RuntimeException.class);
        assertThat(tasks.findById(orphan)).isEmpty();

        var rolledBack = "rollback-" + UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new DocumentRecord(rolledBack, "x", "default", Instant.now()));
            em.flush();
            em.persist(new VectorTask(UUID.randomUUID(), "missing", Instant.now()));
            em.flush();
        })).isInstanceOf(RuntimeException.class);
        assertThat(documents.findById(rolledBack)).isEmpty();

        var oneTask = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new VectorTask(oneTask, id, Instant.now()));
            em.flush();
        });
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new VectorTask(UUID.randomUUID(), id, Instant.now()));
            em.flush();
        })).isInstanceOf(RuntimeException.class);
        assertThat(tasks.findByDocumentId(id).orElseThrow().getTaskId()).isEqualTo(oneTask);
    }

    @Test
    void ddlEnforcesReadinessInvalidationAndNonNegativeCounters() {
        var id = "checks-" + UUID.randomUUID();
        insert(id);
        for (String assignment : new String[] {"hit_count=-1", "vector_ready=true", "invalidated=true"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE document_record SET " + assignment + " WHERE document_id=?", id))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE document_record SET vector_ready=true, vector_bytes=?, "
                        + "vector_version='charhash-v1', vector_completed_at=CURRENT_TIMESTAMP WHERE document_id=?", new byte[8], id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(documents.findById(id).orElseThrow().getHitCount()).isZero();
    }

    @Test
    void schemaProvidesRequiredIndexesAndTaskConstraints() {
        var names = jdbc.queryForList("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_SCHEMA='PUBLIC'", String.class);
        assertThat(names).contains("IDX_TASK_DISPATCH", "IDX_DOCUMENT_SEARCH", "IDX_DOCUMENT_CHANNEL_SEARCH", "IDX_DOCUMENT_SUBMITTED");
        var id = "task-checks-" + UUID.randomUUID();
        insert(id);
        var task = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new VectorTask(task, id, Instant.now()));
            em.flush();
        });
        for (String assignment : new String[] {"status='UNKNOWN'", "status='SUCCEEDED'", "attempt_count=-1", "recovery_count=-1"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE vector_task SET " + assignment + " WHERE task_id=?", task))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    private void insert(String id) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.persist(new DocumentRecord(id, "原文😀\n", "default", Instant.now()));
            em.flush();
        });
    }
}
