package com.example.vectorsearch.vector;

import java.security.MessageDigest;
import java.util.concurrent.CancellationException;

/** Fixed CPU work; its checksum is observable but never influences the vector. */
public final class CpuWorkSimulator {
    public long work(int rounds) {
        return work(rounds, () -> { });
    }

    public long work(int rounds, Runnable checkpoint) {
        if (rounds < 0) {
            throw new IllegalArgumentException("rounds must not be negative");
        }
        MessageDigest digest = Vectorizer.sha256();
        byte[] state = new byte[32];
        for (int round = 0; round < rounds; round++) {
            if ((round & 255) == 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("CPU simulation interrupted");
                }
                checkpoint.run();
            }
            digest.update(state);
            digest.update((byte) (round >>> 24));
            digest.update((byte) (round >>> 16));
            digest.update((byte) (round >>> 8));
            digest.update((byte) round);
            state = digest.digest();
        }
        long checksum = 0;
        for (int i = 0; i < 8; i++) {
            checksum = (checksum << 8) | Byte.toUnsignedInt(state[i]);
        }
        return checksum;
    }
}
