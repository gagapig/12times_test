package com.example.vectorsearch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class VectorProcessDeterminismTest {
    @TempDir Path directory;

    @Test
    void independentJvmOutputMatchesManuallyDerivedGoldenDigest() throws Exception {
        // Python struct.pack('>f') over manually enumerated buckets is the independent oracle.
        assertThat(probe("ab", 0)).isEqualTo("10b25f7fde52bab08c47b8747c1e2bceb81059510d6fedf7af29bb41129c164b");
        assertThat(probe("ab", 1)).isEqualTo(probe("ab", 2));
        assertThat(probe("", 3)).isEqualTo("5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef");
    }

    private String probe(String input, int index) throws Exception {
        Path log = directory.resolve(index + ".txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-cp", classpath, VectorGoldenProbe.class.getName(), input)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            String output = Files.readString(log, StandardCharsets.UTF_8).trim();
            assertThat(process.exitValue()).withFailMessage(output).isZero();
            return output;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
        }
    }
}
