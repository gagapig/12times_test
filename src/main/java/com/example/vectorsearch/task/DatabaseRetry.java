package com.example.vectorsearch.task;

import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Retries a confirmed-rollback transaction only; callers place gate locks inside each attempt. */
@Component
public class DatabaseRetry {
    private final int maxAttempts;

    public DatabaseRetry(TaskSettings settings) { this.maxAttempts = settings.getMaxDbAttempts(); }

    public <T> T run(Supplier<T> attempt) {
        for (int number = 1; ; number++) {
            try {
                return attempt.get();
            } catch (StorageFailure failure) {
                if (failure.kind() != Kind.TRANSIENT_ROLLED_BACK || number >= maxAttempts) {
                    throw failure;
                }
                long backoff = 50L * number;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Database retry interrupted");
                }
            }
        }
    }
}
