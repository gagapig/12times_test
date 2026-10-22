package com.example.vectorsearch;

import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.task.TaskEngine;
import com.example.vectorsearch.vector.EmbeddingPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TaskWorkflowTest.ControlledConfig.class)
class TaskWorkflowTest {
    private static final String DB = Path.of("target", "task-tests", UUID.randomUUID().toString(), "store")
            .toAbsolutePath().toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + DB + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
        registry.add("task.worker-count", () -> 1);
        registry.add("task.queue-capacity", () -> 1);
        registry.add("task.max-pending-tasks", () -> 3);
        registry.add("task.dispatch-interval", () -> "100ms");
        registry.add("task.shutdown-grace", () -> "2s");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ControlledPipeline pipeline;
    @Autowired TaskEngine engine;

    @Test
    void documentTaskDetailAndListReachSuccessfulState() throws Exception {
        String id = unique("normal");
        JsonNode response = submit(id, "Java 中文😀", null, 202);
        UUID taskId = UUID.fromString(response.get("taskId").asText());
        assertThat(response.get("status").asText()).isEqualTo("QUEUED");
        JsonNode done = waitFor(taskId, "SUCCEEDED");
        assertThat(done.get("document").get("documentId").asText()).isEqualTo(id);
        assertThat(done.get("document").get("channel").asText()).isEqualTo("default");
        assertThat(done.get("document").get("vectorReady").asBoolean()).isTrue();
        assertThat(done.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(getJson("/api/documents/" + id).get("text").asText()).isEqualTo("Java 中文😀");
        assertThat(getJson("/api/documents/" + id + "/task").get("taskId").asText()).isEqualTo(taskId.toString());
        JsonNode page = getJson("/api/documents?page=0&size=100&channel=default&vectorReady=true");
        assertThat(page.get("items").toString()).contains(id);
        assertThat(page.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void boundedQueueLeavesAcceptedTasksForRescanAndRejectsBacklogOverflow() throws Exception {
        pipeline.block();
        String first = unique("first"), second = unique("second"), third = unique("third"), fourth = unique("fourth");
        try {
            UUID firstTask = UUID.fromString(submit(first, "a", "中文渠道", 202).get("taskId").asText());
            assertThat(pipeline.awaitEntered()).isTrue();
            assertThat(getJson("/api/tasks/" + firstTask).get("status").asText()).isEqualTo("RUNNING");
            UUID secondTask = UUID.fromString(submit(second, "b", null, 202).get("taskId").asText());
            await(() -> engine.scheduledCount() == 2);
            UUID thirdTask = UUID.fromString(submit(third, "c", null, 202).get("taskId").asText());
            assertThat(getJson("/api/tasks/" + thirdTask).get("status").asText()).isEqualTo("QUEUED");
            submit(fourth, "d", null, 503);
            assertThat(getStatus("/api/documents/" + fourth)).isEqualTo(404);
            pipeline.release();
            waitFor(firstTask, "SUCCEEDED");
            waitFor(secondTask, "SUCCEEDED");
            waitFor(thirdTask, "SUCCEEDED");
        } finally { pipeline.release(); }
    }

    @Test
    void duplicateDocumentIdIsConflictEvenWhenSubmittedConcurrently() throws Exception {
        String id = unique("duplicate");
        ExecutorService threads = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var attempts = java.util.stream.IntStream.range(0, 6)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        start.await();
                        return getSubmitStatus(id, "same", null);
                    }).toList();
            var futures = attempts.stream().map(threads::submit).toList();
            start.countDown();
            int accepted = 0;
            for (var future : futures) {
                int code = future.get(15, TimeUnit.SECONDS);
                if (code == 202) accepted++;
                else assertThat(code).isEqualTo(409);
            }
            assertThat(accepted).isEqualTo(1);
            var task = getJson("/api/documents/" + id + "/task");
            waitFor(UUID.fromString(task.get("taskId").asText()), "SUCCEEDED");
        } finally { start.countDown(); threads.shutdownNow(); }
    }

    @Test
    void failedVectorizationStaysFailedAndIsNeverReady() throws Exception {
        String id = unique("failed");
        UUID task = UUID.fromString(submit(id, "FAIL_MARKER", null, 202).get("taskId").asText());
        JsonNode failure = waitFor(task, "FAILED");
        assertThat(failure.get("error").get("code").asText()).isEqualTo("VECTORIZE_FAILED");
        assertThat(failure.get("document").isNull()).isTrue();
        JsonNode document = getJson("/api/documents/" + id);
        assertThat(document.get("vectorReady").asBoolean()).isFalse();
        assertThat(document.get("completedAt").isNull()).isTrue();
        assertThat(getStatus("/api/tasks/invalid")).isEqualTo(400);
        assertThat(getStatus("/api/documents?taskStatus=GARBAGE")).isEqualTo(400);
    }

    private JsonNode submit(String id, String text, String channel, int expected) throws Exception {
        String body = json.writeValueAsString(channel == null
                ? java.util.Map.of("documentId", id, "text", text)
                : java.util.Map.of("documentId", id, "text", text, "channel", channel));
        var result = mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(result.getStatus()).withFailMessage(result.getContentAsString()).isEqualTo(expected);
        return json.readTree(result.getContentAsString());
    }

    private int getSubmitStatus(String id, String text, String channel) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("documentId", id, "text", text));
        return mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode getJson(String path) throws Exception {
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private int getStatus(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    private JsonNode waitFor(UUID taskId, String target) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            JsonNode task = getJson("/api/tasks/" + taskId);
            if (task.get("status").asText().equals(target)) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("Task did not reach " + target + ": " + taskId);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (condition.call()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Condition not reached");
    }

    private static String unique(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    @TestConfiguration
    static class ControlledConfig {
        @Bean @Primary ControlledPipeline controlledPipeline() { return new ControlledPipeline(); }
    }

    static class ControlledPipeline extends EmbeddingPipeline {
        private final AtomicBoolean blocking = new AtomicBoolean();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch resume = new CountDownLatch(0);

        ControlledPipeline() { super(25); }

        void block() {
            entered = new CountDownLatch(1);
            resume = new CountDownLatch(1);
            blocking.set(true);
        }

        boolean awaitEntered() throws InterruptedException { return entered.await(5, TimeUnit.SECONDS); }
        void release() { blocking.set(false); resume.countDown(); }

        @Override
        public EmbeddingResult embed(String text) {
            if (blocking.get()) {
                entered.countDown();
                try { resume.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Test worker interrupted");
                }
            }
            if (text.equals("FAIL_MARKER")) throw new IllegalStateException("intentional test failure");
            return super.embed(text);
        }
    }
}
