package com.example.vectorsearch.api;

import java.util.List;

public record SearchResponse(int topK, int returnedCount, List<Result> results) {
    public record Result(DocumentDetail document, double score) { }
}
