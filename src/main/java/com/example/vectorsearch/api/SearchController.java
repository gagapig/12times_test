package com.example.vectorsearch.api;

import com.example.vectorsearch.search.SearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SearchController {
    private final SearchService service;

    public SearchController(SearchService service) { this.service = service; }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return service.search(request);
    }
}
