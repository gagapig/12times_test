package com.example.vectorsearch;

import com.example.vectorsearch.api.*;
import com.example.vectorsearch.config.JsonConfiguration;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Test-only routes exercise contracts without publishing unfinished business APIs. */
@WebMvcTest(ApiContractTest.ProbeController.class)
@Import({JsonConfiguration.class, ApiExceptionHandler.class, RequestBoundaryFilter.class,
        ApiContractTest.ProbeController.class})
class ApiContractTest {
    @Autowired MockMvc mvc;

    @Test
    void missingChannelDefaultsAndEmptyTextIsAccepted() throws Exception {
        mvc.perform(post("/api/_contract/doc").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"text\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.channel").value("default"));
    }

    @ParameterizedTest
    @MethodSource("validChannels")
    void chineseAndUnicodeChannelsArePreserved(String channel) throws Exception {
        mvc.perform(post("/api/_contract/doc").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"text\":\"原文😀\",\"channel\":\"" + channel + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.channel").value(channel));
    }

    static Stream<String> validChannels() {
        return Stream.of("中文渠道", "知识 库", "a_B-1", "中".repeat(64), "😀".repeat(32));
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void invalidDocumentJsonIsRejectedWithoutEchoingContent(String json) throws Exception {
        mvc.perform(post("/api/_contract/doc").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    static Stream<String> invalidDocuments() {
        return Stream.of(
                "{}", "{\"documentId\":\"id\"}", "{\"documentId\":\"id\",\"text\":null}",
                "{\"documentId\":\"中文ID\",\"text\":\"x\"}",
                "{\"documentId\":3,\"text\":\"x\"}",
                "{\"documentId\":\"id\",\"text\":true}",
                "{\"documentId\":\"id\",\"text\":4}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"typo\":1}",
                "{\"documentId\":\"id\",\"text\":\"x\"} {}",
                "{\"documentId\":\"id\",\"text\":\"\\uD800\"}",
                "{\"documentId\":\"id\",\"text\":\"" + "x".repeat(20_001) + "\"}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"channel\":null}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"channel\":\"\"}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"channel\":\" 中文\"}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"channel\":\"中文　\"}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"channel\":\"中文\\n渠道\"}",
                "{\"documentId\":\"id\",\"text\":\"x\",\"channel\":\"" + "中".repeat(65) + "\"}");
    }

    @Test
    void emptySearchContractIsAcceptedWithoutDefaultChannel() throws Exception {
        mvc.perform(post("/api/_contract/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\",\"topK\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.topK").value(2));
    }

    @ParameterizedTest
    @MethodSource("invalidSearches")
    void topKHasStrictTypeAndBounds(String json) throws Exception {
        mvc.perform(post("/api/_contract/search").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());
    }

    static Stream<String> invalidSearches() {
        return Stream.of("{\"text\":\"x\"}", "{\"text\":\"x\",\"topK\":0}",
                "{\"text\":\"x\",\"topK\":101}", "{\"text\":\"x\",\"topK\":\"2\"}",
                "{\"text\":\"x\",\"topK\":2.0}", "{\"text\":\"x\",\"topK\":true}",
                "{\"text\":\"x\",\"topK\":2,\"channel\":null}");
    }

    @Test
    void bodyLimitRunsBeforeJsonParsing() throws Exception {
        mvc.perform(post("/api/_contract/doc").contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[RequestBoundaryFilter.MAX_BODY_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }

    @Test
    void unsupportedMediaAndKnownBusinessConflictHaveStableErrors() throws Exception {
        mvc.perform(post("/api/_contract/doc").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/_contract/conflict"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_EXISTS"));
        mvc.perform(get("/api/_contract/integrity"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("数据约束异常"));
    }

    @RestController
    static class ProbeController {
        @PostMapping(value = "/api/_contract/doc", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> document(@Valid @RequestBody SubmitDocumentRequest request) {
            return Map.of("channel", request.getChannel());
        }
        @PostMapping(value = "/api/_contract/search", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Integer> search(@Valid @RequestBody SearchRequest request) {
            return Map.of("topK", request.getTopK());
        }
        @GetMapping("/api/_contract/conflict")
        void conflict() {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_EXISTS", "文档已存在");
        }
        @GetMapping("/api/_contract/integrity")
        void integrity() { throw new DataIntegrityViolationException("private database detail"); }
    }
}
