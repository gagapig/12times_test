package com.example.vectorsearch.vector;

/** Fixed-order double accumulations preserve deterministic ranking. */
public final class Similarity {
    private Similarity() { }

    public static double cosine(float[] query, float[] candidate) {
        if (query == null || candidate == null || query.length != Vectorizer.DIMENSIONS
                || candidate.length != Vectorizer.DIMENSIONS) {
            throw new IllegalArgumentException("Expected two 256-dimensional vectors");
        }
        double dot = 0.0;
        double querySquared = 0.0;
        double candidateSquared = 0.0;
        for (int i = 0; i < Vectorizer.DIMENSIONS; i++) {
            if (!Float.isFinite(query[i]) || !Float.isFinite(candidate[i])) {
                throw new IllegalArgumentException("Non-finite vector value");
            }
            dot += (double) query[i] * candidate[i];
            querySquared += (double) query[i] * query[i];
            candidateSquared += (double) candidate[i] * candidate[i];
        }
        if (querySquared == 0 || candidateSquared == 0) {
            return 0.0;
        }
        double score = dot / (StrictMath.sqrt(querySquared) * StrictMath.sqrt(candidateSquared));
        return Math.max(-1.0, Math.min(1.0, score)) + 0.0;
    }
}
