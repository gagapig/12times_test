package com.example.vectorsearch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class FileRestartTest {
    @TempDir Path directory;

    @Test
    void twoIndependentJavaProcessesKeepDataAndRejectIncompatibleSchema() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("store") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        assertThat(probe("write", url, "first", 0)).contains("PROBE_WRITE_OK");
        assertThat(Files.exists(directory.resolve("store.mv.db"))).isTrue();
        assertThat(probe("read", url, "second", 0)).contains("PROBE_READ_OK");

        // Keep schema.sql idempotent while deliberately changing an unmapped-compatible index-free column type.
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE document_record ALTER COLUMN original_text VARCHAR(20000)");
        }
        assertThat(probe("read", url, "incompatible", 1)).contains("Schema-validation");
    }

    private String probe(String mode, String url, String label, int expectedExit) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = directory.resolve(label + ".log");
        Process child = new ProcessBuilder(java, "-cp", classpath, FileDatabaseProbe.class.getName(), mode, url)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(45, TimeUnit.SECONDS)).as("probe completes: %s", label).isTrue();
            String output = Files.readString(log);
            assertThat(child.exitValue()).withFailMessage(output).isEqualTo(expectedExit);
            return output;
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
