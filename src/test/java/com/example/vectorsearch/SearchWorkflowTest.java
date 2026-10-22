package com.example.vectorsearch;

import com.example.vectorsearch.vector.EmbeddingPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"task.worker-count=1", "task.dispatch-interval=100ms", "vector.simulation-rounds=50"})
@AutoConfigureMockMvc
@Import(SearchWorkflowTest.ControlledConfig.class)
class SearchWorkflowTest {
    private static final String DB = Path.of("target", "search-tests", UUID.randomUUID().toString(), "store")
            .toAbsolutePath().toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + DB + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ControlledPipeline pipeline;

    @Test
    void channelFilteringEmptyQueryFinalTopKCountsAndInvalidation() throws Exception {
        String prefix = "s-" + UUID.randomUUID() + "-";
        String a = prefix + "a", b = prefix + "b", c = prefix + "c", d = prefix + "d";
        submit(a, "Java", null);
        submit(b, "", null);
        submit(c, "Java", "招聘");
        submit(d, "unrelated", null);
        for (String id : new String[]{a, b, c, d}) waitFor(id, "SUCCEEDED");

        JsonNode first = search("Java", 1, "default", 200);
        assertThat(ids(first)).containsExactly(a);
        assertThat(first.get("results").get(0).get("score").asDouble()).isCloseTo(1.0, within(0.00001));
        assertThat(detail(a).get("hitCount").asLong()).isEqualTo(1);
        assertThat(detail(b).get("hitCount").asLong()).isZero();
        assertThat(detail(d).get("hitCount").asLong()).isZero();

        JsonNode empty = search("", 2, "default", 200);
        assertThat(ids(empty)).containsExactly(a, b);
        assertThat(empty.get("results").get(0).get("score").asDouble()).isZero();
        assertThat(empty.get("results").get(1).get("score").asDouble()).isZero();
        assertThat(detail(a).get("hitCount").asLong()).isEqualTo(2);
        assertThat(detail(b).get("hitCount").asLong()).isEqualTo(1);
        assertThat(detail(c).get("hitCount").asLong()).isZero();
        assertThat(detail(d).get("hitCount").asLong()).isZero();

        assertThat(ids(search("Java", 5, "招聘", 200))).containsExactly(c);
        JsonNode invalid = putInvalidation(a, null, 200);
        assertThat(invalid.get("invalidated").asBoolean()).isTrue();
        String firstAt = invalid.get("invalidatedAt").asText();
        assertThat(putInvalidation(a, null, 200).get("invalidatedAt").asText()).isEqualTo(firstAt);
        assertThat(ids(search("Java", 5, "default", 200))).doesNotContain(a, c);
        assertThat(detail(a).get("hitCount").asLong()).isEqualTo(2);
        assertThat(ids(search("Java", 5, "absent", 200))).isEmpty();
    }

    @Test
    void finishingAnInvalidatedRunningTaskDoesNotClearItsMarker() throws Exception {
        String id = "running-" + UUID.randomUUID();
        pipeline.block();
        try {
            submit(id, "DELAY", null);
            assertThat(pipeline.awaitEntered()).isTrue();
            assertThat(task(id).get("status").asText()).isEqualTo("RUNNING");
            JsonNode invalid = putInvalidation(id, null, 200);
            String at = invalid.get("invalidatedAt").asText();
            pipeline.release();
            waitFor(id, "SUCCEEDED");
            JsonNode detail = detail(id);
            assertThat(detail.get("vectorReady").asBoolean()).isTrue();
            assertThat(detail.get("invalidated").asBoolean()).isTrue();
            assertThat(detail.get("invalidatedAt").asText()).isEqualTo(at);
            assertThat(task(id).get("document").get("invalidated").asBoolean()).isTrue();
            assertThat(ids(search("DELAY", 100, null, 200))).doesNotContain(id);
        } finally { pipeline.release(); }
    }

    @Test
    void invalidParametersAndDamagedVectorsFailExplicitly() throws Exception {
        String id = "damaged-" + UUID.randomUUID();
        submit(id, "x", null);
        waitFor(id, "SUCCEEDED");
        search("x", 0, null, 400);
        assertThat(postStatus("/api/search", "{\"text\":\"x\",\"topK\":1,\"extra\":1}"))
                .isEqualTo(400);
        assertThat(postStatus("/api/search", "{\"text\":\"x\",\"topK\":1,\"channel\":\"\"}"))
                .isEqualTo(400);
        putInvalidation(id, "{}", 400);
        putInvalidation("missing-" + UUID.randomUUID(), null, 404);
        jdbc.update("UPDATE document_record SET vector_version='unsupported' WHERE document_id=?", id);
        try {
            JsonNode error = search("x", 1, null, 500);
            assertThat(error.get("code").asText()).isEqualTo("VECTOR_DATA_INVALID");
        } finally {
            jdbc.update("UPDATE document_record SET vector_version='charhash-v1' WHERE document_id=?", id);
        }
    }

    private void submit(String id, String text, String channel) throws Exception {
        var body = channel == null ? java.util.Map.of("documentId", id, "text", text)
                : java.util.Map.of("documentId", id, "text", text, "channel", channel);
        var response = mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(202);
    }

    private JsonNode search(String text, int topK, String channel, int expected) throws Exception {
        var body = channel == null ? java.util.Map.of("text", text, "topK", topK)
                : java.util.Map.of("text", text, "topK", topK, "channel", channel);
        var response = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString());
    }

    private int postStatus(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode putInvalidation(String id, String body, int expected) throws Exception {
        var request = put("/api/documents/" + id + "/invalidation");
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode detail(String id) throws Exception {
        return get("/api/documents/" + id);
    }

    private JsonNode task(String id) throws Exception {
        return get("/api/documents/" + id + "/task");
    }

    private JsonNode get(String path) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andReturn().getResponse().getContentAsString());
    }

    private void waitFor(String id, String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (task(id).get("status").asText().equals(status)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Task for " + id + " did not reach " + status);
    }

    private static String[] ids(JsonNode response) {
        var results = response.get("results");
        String[] ids = new String[results.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = results.get(i).get("document").get("documentId").asText();
        return ids;
    }

    @TestConfiguration
    static class ControlledConfig {
        @Bean @Primary ControlledPipeline controlledPipeline() { return new ControlledPipeline(); }
    }

    static class ControlledPipeline extends EmbeddingPipeline {
        private final AtomicBoolean blocking = new AtomicBoolean();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        ControlledPipeline() { super(50); }
        void block() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            blocking.set(true);
        }
        boolean awaitEntered() throws InterruptedException { return entered.await(5, TimeUnit.SECONDS); }
        void release() { blocking.set(false); release.countDown(); }
        @Override public EmbeddingResult embed(String text) {
            if (blocking.get() && text.equals("DELAY")) {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("interrupted");
                }
            }
            return super.embed(text);
        }
    }
}
