package com.example.vectorsearch.vector;

import com.example.vectorsearch.validation.TextValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The same pipeline is used by document workers and future synchronous search. */
@Component
public class EmbeddingPipeline {
    private final Vectorizer vectorizer;
    private final CpuWorkSimulator simulator;
    private final int simulationRounds;

    public EmbeddingPipeline(@Value("${vector.simulation-rounds:20000}") int simulationRounds) {
        if (simulationRounds < 0) {
            throw new IllegalArgumentException("vector.simulation-rounds must be non-negative");
        }
        this.vectorizer = new Vectorizer();
        this.simulator = new CpuWorkSimulator();
        this.simulationRounds = simulationRounds;
    }

    public EmbeddingResult embed(String text) {
        return embed(text, () -> { });
    }

    public EmbeddingResult embed(String text, Runnable checkpoint) {
        if (!TextValidator.isWellFormed(text)) {
            throw new IllegalArgumentException("Invalid text");
        }
        long checksum = simulator.work(simulationRounds, checkpoint);
        checkpoint.run();
        return new EmbeddingResult(vectorizer.vectorize(text, checkpoint), checksum);
    }

    public record EmbeddingResult(float[] vector, long simulationChecksum) {
        public EmbeddingResult {
            vector = vector.clone();
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }
    }
}
