package com.example.vectorsearch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessRestartTest {
    @TempDir Path directory;

    @Test
    void independentProcessesKeepBusinessResultsAndRecoverUnfinishedTasks() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("store") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        assertThat(probe("write", url)).contains("BUSINESS_WRITE_OK");
        assertThat(Files.exists(directory.resolve("store.mv.db"))).isTrue();
        assertThat(probe("read", url)).contains("BUSINESS_RESTART_OK");
    }

    private String probe(String mode, String url) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = directory.resolve(mode + ".log");
        Process child = new ProcessBuilder(java, "-cp", classpath, BusinessRestartProbe.class.getName(), mode, url)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(50, TimeUnit.SECONDS)).as("probe completes: %s", mode).isTrue();
            String output = Files.readString(log);
            assertThat(child.exitValue()).withFailMessage(output).isZero();
            return output;
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
