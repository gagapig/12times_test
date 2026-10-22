package com.example.vectorsearch.api;

import java.util.List;

public record DocumentPage(List<DocumentSummary> items, int page, int size,
                           long totalElements, long totalPages) { }
