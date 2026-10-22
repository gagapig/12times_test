package com.example.vectorsearch.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Stable IEEE-754 binary32 serialization; no Java object serialization. */
public final class VectorCodec {
    public static final String VERSION = "charhash-v1";
    public static final int BYTE_LENGTH = Vectorizer.DIMENSIONS * Float.BYTES;

    private VectorCodec() { }

    public static byte[] encode(float[] vector) {
        validate(vector);
        ByteBuffer bytes = ByteBuffer.allocate(BYTE_LENGTH).order(ByteOrder.BIG_ENDIAN);
        for (float value : vector) {
            bytes.putFloat(value);
        }
        return bytes.array();
    }

    public static float[] decode(byte[] bytes, String version) {
        if (!VERSION.equals(version) || bytes == null || bytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException("Unsupported or damaged vector format");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] vector = new float[Vectorizer.DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = input.getFloat();
        }
        validate(vector);
        return vector;
    }

    private static void validate(float[] vector) {
        if (vector == null || vector.length != Vectorizer.DIMENSIONS) {
            throw new IllegalArgumentException("Vector must have 256 dimensions");
        }
        for (float value : vector) {
            if (!Float.isFinite(value) || value < 0 || Float.floatToRawIntBits(value) == 0x80000000) {
                throw new IllegalArgumentException("Vector contains a non-finite or negative value");
            }
        }
    }
}
