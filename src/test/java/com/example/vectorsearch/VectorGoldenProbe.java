package com.example.vectorsearch;

import com.example.vectorsearch.vector.VectorCodec;
import com.example.vectorsearch.vector.Vectorizer;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Invoked by another JVM so the golden byte check is not only in-process. */
public class VectorGoldenProbe {
    public static void main(String[] args) throws Exception {
        byte[] bytes = VectorCodec.encode(new Vectorizer().vectorize(args[0]));
        System.out.println(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
