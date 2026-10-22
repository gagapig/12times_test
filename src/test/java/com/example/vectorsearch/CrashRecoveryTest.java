package com.example.vectorsearch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class CrashRecoveryTest {
    @TempDir Path directory;

    @Test
    void killedWorkerProcessLeavesRunningTaskForNextProcessToRecover() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("store") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
        Path firstLog = directory.resolve("first.log");
        Process first = new ProcessBuilder(command("start", url)).redirectErrorStream(true)
                .redirectOutput(firstLog.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && first.isAlive()) {
                if (Files.exists(firstLog) && Files.readString(firstLog).contains("CRASH_RUNNING_DURABLE")) break;
                Thread.sleep(50);
            }
            assertThat(Files.readString(firstLog)).contains("CRASH_RUNNING_DURABLE");
        } finally {
            first.destroyForcibly();
            first.waitFor(5, TimeUnit.SECONDS);
        }

        Path secondLog = directory.resolve("second.log");
        Process second = new ProcessBuilder(command("resume", url)).redirectErrorStream(true)
                .redirectOutput(secondLog.toFile()).start();
        try {
            assertThat(second.waitFor(45, TimeUnit.SECONDS)).isTrue();
            String output = Files.readString(secondLog);
            assertThat(second.exitValue()).withFailMessage(output).isZero();
            assertThat(output).contains("CRASH_RECOVERED_OK");
        } finally {
            if (second.isAlive()) { second.destroyForcibly(); second.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private static String[] command(String mode, String url) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new String[]{java, "-cp", classpath, CrashRecoveryProbe.class.getName(), mode, url};
    }
}
