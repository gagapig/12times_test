package com.example.vectorsearch;

import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Outcome;
import com.example.vectorsearch.task.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DatabaseRetryTest {
    @Test
    void retriesOnlyConfirmedRollbackAndKeepsVectorAttemptSeparate() {
        var settings = new TaskSettings();
        var retry = new DatabaseRetry(settings);
        var transactionAttempts = new AtomicInteger();
        var vectorExecutions = new AtomicInteger(1);
        assertThat(retry.run(() -> {
            if (transactionAttempts.incrementAndGet() < 3) {
                throw new StorageFailure(Kind.TRANSIENT_ROLLED_BACK, Outcome.ROLLED_BACK,
                        new IllegalStateException("lock conflict"));
            }
            return "committed";
        })).isEqualTo("committed");
        assertThat(transactionAttempts).hasValue(3);
        assertThat(vectorExecutions).hasValue(1);
        var unknown = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> {
            unknown.incrementAndGet();
            throw new StorageFailure(Kind.OUTCOME_UNKNOWN, Outcome.UNKNOWN, new IllegalStateException("commit?"));
        })).isInstanceOf(StorageFailure.class);
        assertThat(unknown).hasValue(1);
    }
}
