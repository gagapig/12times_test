package com.example.vectorsearch;

import com.example.vectorsearch.vector.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VectorAlgorithmTest {
    private final Vectorizer vectorizer = new Vectorizer();

    @Test
    void handCheckedBucketsForAbAndUnicodeSupplementaryCodePoint() {
        float[] ab = vectorizer.vectorize("ab");
        // Independently calculated SHA-256 first bytes: 'a'=249, 'b'=138, 'ab'=68.
        assertThat(ab[249]).isEqualTo((float) (1 / StrictMath.sqrt(6)));
        assertThat(ab[138]).isEqualTo((float) (1 / StrictMath.sqrt(6)));
        assertThat(ab[68]).isEqualTo((float) (2 / StrictMath.sqrt(6)));
        assertThat(VectorCodec.encode(ab)).hasSize(1024);
        assertThat(nonZero(ab)).isEqualTo(3);

        float[] unicode = vectorizer.vectorize("中😀");
        // Code points U+4E2D and U+1F600: 132, 82, adjacent pair 161.
        assertThat(unicode[132]).isEqualTo((float) (1 / StrictMath.sqrt(6)));
        assertThat(unicode[82]).isEqualTo((float) (1 / StrictMath.sqrt(6)));
        assertThat(unicode[161]).isEqualTo((float) (2 / StrictMath.sqrt(6)));
        assertThat(nonZero(unicode)).isEqualTo(3);
    }

    @Test
    void emptyTextIsAllPositiveZeroAndBadTextIsRejected() {
        float[] zero = vectorizer.vectorize("");
        assertThat(zero).hasSize(256);
        for (float value : zero) {
            assertThat(Float.floatToRawIntBits(value)).isZero();
        }
        assertThatThrownBy(() -> vectorizer.vectorize(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vectorizer.vectorize("\uD800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> vectorizer.vectorize("a".repeat(20_001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(vectorizer.vectorize("  ")).isNotEqualTo(zero);
    }

    @Test
    void repeatAndParallelCallsProduceExactlyTheSameBytes() throws Exception {
        String input = "Java 中😀\nJava";
        byte[] reference = VectorCodec.encode(vectorizer.vectorize(input));
        var threads = Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 48)
                    .<java.util.concurrent.Callable<byte[]>>mapToObj(i -> () -> VectorCodec.encode(vectorizer.vectorize(input)))
                    .toList();
            for (var result : threads.invokeAll(tasks)) {
                assertThat(result.get()).containsExactly(reference);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void encodingHasKnownBigEndianBytesAndRejectsDamagedVectors() {
        float[] vector = new float[256];
        vector[0] = 1.0f;
        vector[1] = 0.5f;
        byte[] bytes = VectorCodec.encode(vector);
        assertThat(bytes).hasSize(1024);
        assertThat(Arrays.copyOfRange(bytes, 0, 8))
                .containsExactly(new byte[] {0x3f, (byte) 0x80, 0, 0, 0x3f, 0, 0, 0});
        assertThat(VectorCodec.decode(bytes, VectorCodec.VERSION)).containsExactly(vector);
        assertThatThrownBy(() -> VectorCodec.decode(new byte[8], VectorCodec.VERSION)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VectorCodec.decode(bytes, "other-version")).isInstanceOf(IllegalArgumentException.class);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putFloat(Float.NaN);
        assertThatThrownBy(() -> VectorCodec.decode(bytes, VectorCodec.VERSION)).isInstanceOf(IllegalArgumentException.class);
        vector[0] = Float.POSITIVE_INFINITY;
        assertThatThrownBy(() -> VectorCodec.encode(vector)).isInstanceOf(IllegalArgumentException.class);
        vector[0] = -0.0f;
        assertThatThrownBy(() -> VectorCodec.encode(vector)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cosineHandlesSameOrthogonalAndZeroVectors() {
        float[] a = new float[256];
        float[] b = new float[256];
        a[0] = 1f;
        b[1] = 1f;
        assertThat(Similarity.cosine(a, a)).isCloseTo(1.0, within(1e-12));
        assertThat(Similarity.cosine(a, b)).isZero();
        assertThat(Similarity.cosine(a, new float[256])).isZero();
        assertThat(Similarity.cosine(new float[256], new float[256])).isZero();
    }

    @Test
    void heapMatchesFullSortForRandomInputAndTieBreaksById() {
        Random random = new Random(4021);
        var full = new java.util.ArrayList<TopKSelector.Hit>();
        for (int i = 0; i < 800; i++) {
            full.add(new TopKSelector.Hit(String.format("doc-%04d", i), random.nextInt(20) / 19.0));
        }
        var ordered = full.stream().sorted(java.util.Comparator.comparingDouble(TopKSelector.Hit::score).reversed()
                .thenComparing(TopKSelector.Hit::documentId)).toList();
        for (int k : List.of(1, 2, 10, 100)) {
            TopKSelector selector = new TopKSelector(k);
            full.forEach(hit -> selector.offer(hit.documentId(), hit.score()));
            assertThat(selector.sorted()).isEqualTo(ordered.subList(0, k));
            assertThat(selector.sorted()).isEqualTo(ordered.subList(0, k));
        }
        TopKSelector ties = new TopKSelector(2);
        ties.offer("C", 0); ties.offer("B", 0); ties.offer("A", 0);
        assertThat(ties.sorted()).extracting(TopKSelector.Hit::documentId).containsExactly("A", "B");
        assertThat(new TopKSelector(100).sorted()).isEmpty();
    }

    @Test
    void cpuSimulationIsObservableButDoesNotAlterVectorAndHonorsInterrupt() {
        var pipeline = new EmbeddingPipeline(200);
        var fast = new EmbeddingPipeline(0);
        assertThat(VectorCodec.encode(pipeline.embed("text").vector()))
                .containsExactly(VectorCodec.encode(fast.embed("text").vector()));
        assertThat(pipeline.embed("text").simulationChecksum()).isNotZero();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> new CpuWorkSimulator().work(20000)).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
    }

    private static long nonZero(float[] vector) {
        long count = 0;
        for (float value : vector) {
            if (value != 0f) count++;
        }
        return count;
    }
}
