package com.example.vectorsearch.vector;

import com.example.vectorsearch.validation.TextValidator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CancellationException;

/** charhash-v1: Unicode unigrams (weight 1) and adjacent bigrams (weight 2). */
public final class Vectorizer {
    public static final int DIMENSIONS = 256;

    public float[] vectorize(String text) {
        return vectorize(text, () -> { });
    }

    public float[] vectorize(String text, Runnable checkpoint) {
        if (!TextValidator.isWellFormed(text)) {
            throw new IllegalArgumentException("Invalid text");
        }
        int[] counts = new int[DIMENSIONS];
        MessageDigest sha = sha256(); // local: MessageDigest is not thread-safe
        int previous = -1;
        int seen = 0;
        for (int offset = 0; offset < text.length();) {
            if ((seen++ & 255) == 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Vectorization interrupted");
                }
                checkpoint.run();
            }
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            counts[bucket(sha, 1, codePoint, -1)]++;
            if (previous != -1) {
                counts[bucket(sha, 2, previous, codePoint)] += 2;
            }
            previous = codePoint;
        }

        float[] vector = new float[DIMENSIONS];
        double squared = 0.0;
        for (int value : counts) {
            squared += (double) value * value;
        }
        if (squared == 0.0) {
            return vector; // Java initializes every entry to positive zero.
        }
        double norm = StrictMath.sqrt(squared);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) (counts[i] / norm);
        }
        return vector;
    }

    private static int bucket(MessageDigest sha, int tag, int first, int second) {
        sha.update((byte) tag);
        updateCodePoint(sha, first);
        if (tag == 2) {
            updateCodePoint(sha, second);
        }
        return Byte.toUnsignedInt(sha.digest()[0]);
    }

    private static void updateCodePoint(MessageDigest sha, int codePoint) {
        sha.update((byte) (codePoint >>> 24));
        sha.update((byte) (codePoint >>> 16));
        sha.update((byte) (codePoint >>> 8));
        sha.update((byte) codePoint);
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", failure);
        }
    }
}
