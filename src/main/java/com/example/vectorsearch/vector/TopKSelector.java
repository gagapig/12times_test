package com.example.vectorsearch.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Exact bounded heap; filtering and score calculation are upstream concerns. */
public final class TopKSelector {
    public record Hit(String documentId, double score) {
        public Hit {
            if (documentId == null || documentId.isEmpty() || !Double.isFinite(score)) {
                throw new IllegalArgumentException("Invalid search hit");
            }
        }
    }

    private static final Comparator<Hit> WORST_FIRST = Comparator.comparingDouble(Hit::score)
            .thenComparing(Hit::documentId, Comparator.reverseOrder());
    private final PriorityQueue<Hit> heap = new PriorityQueue<>(WORST_FIRST);
    private final int capacity;

    public TopKSelector(int capacity) {
        if (capacity < 1 || capacity > 100) {
            throw new IllegalArgumentException("topK must be 1..100");
        }
        this.capacity = capacity;
    }

    public void offer(String documentId, double score) {
        Hit hit = new Hit(documentId, score);
        if (heap.size() < capacity) {
            heap.add(hit);
        } else if (WORST_FIRST.compare(hit, heap.peek()) > 0) {
            heap.remove();
            heap.add(hit);
        }
    }

    public List<Hit> sorted() {
        List<Hit> result = new ArrayList<>(heap);
        result.sort(WORST_FIRST.reversed());
        return List.copyOf(result);
    }
}
