package com.example.vectorsearch;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.task.TaskStore;
import com.example.vectorsearch.vector.TopKSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"vector.simulation-rounds=0", "search.max-concurrent=8",
                "spring.datasource.hikari.maximum-pool-size=12"})
@AutoConfigureMockMvc
class SearchConcurrencyTest {
    private static final String DB = Path.of("target", "concurrency-tests", UUID.randomUUID().toString(), "store")
            .toAbsolutePath().toString();
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + DB
                + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;LOCK_TIMEOUT=5000");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskStore store;

    @Test
    void simultaneousSearchesAtomicallyCountOnlyReturnedDocuments() throws Exception {
        String prefix = "count-" + UUID.randomUUID() + "-";
        String a = prefix + "a", b = prefix + "b", c = prefix + "c";
        for (String id : List.of(a, b, c)) submit(id);
        for (String id : List.of(a, b, c)) waitFor(id);

        int requests = 6;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> futures = java.util.stream.IntStream.range(0, requests)
                    .<Callable<JsonNode>>mapToObj(i -> () -> {
                        start.await();
                        var response = mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"\",\"topK\":2}"))
                                .andReturn().getResponse();
                        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
                        return json.readTree(response.getContentAsString());
                    }).map(pool::submit).toList();
            start.countDown();
            for (Future<JsonNode> future : futures) {
                var hits = future.get(20, TimeUnit.SECONDS).get("results");
                assertThat(hits.size()).isEqualTo(2);
                assertThat(hits.get(0).get("document").get("documentId").asText()).isEqualTo(a);
                assertThat(hits.get(1).get("document").get("documentId").asText()).isEqualTo(b);
            }
            assertThat(store.document(a).orElseThrow().hitCount()).isEqualTo(requests);
            assertThat(store.document(b).orElseThrow().hitCount()).isEqualTo(requests);
            assertThat(store.document(c).orElseThrow().hitCount()).isZero();
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failureAfterFirstCountUpdateRollsBackTheWholeTopKTransaction() throws Exception {
        String prefix = "rollback-" + UUID.randomUUID() + "-";
        String a = prefix + "a", b = prefix + "b";
        submit(a); submit(b);
        waitFor(a); waitFor(b);
        AtomicInteger checks = new AtomicInteger();
        List<TopKSelector.Hit> hits = List.of(new TopKSelector.Hit(a, 0), new TopKSelector.Hit(b, 0));

        assertThatThrownBy(() -> store.countAndLoad(hits, () -> {
            if (checks.incrementAndGet() == 2) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_CANCELLED", "injected cancellation");
            }
        }, 5)).isInstanceOf(ApiException.class);
        assertThat(store.document(a).orElseThrow().hitCount()).isZero();
        assertThat(store.document(b).orElseThrow().hitCount()).isZero();
    }

    private void submit(String id) throws Exception {
        var response = mvc.perform(post("/api/documents").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(java.util.Map.of("documentId", id, "text", ""))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(202);
    }

    private void waitFor(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            var response = mvc.perform(get("/api/documents/" + id + "/task")).andReturn().getResponse();
            if (json.readTree(response.getContentAsString()).get("status").asText().equals("SUCCEEDED")) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Task did not complete: " + id);
    }
}
