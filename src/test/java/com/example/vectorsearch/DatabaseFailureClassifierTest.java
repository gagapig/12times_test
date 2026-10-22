package com.example.vectorsearch;

import com.example.vectorsearch.persistence.DatabaseFailureClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import static com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind.*;
import static com.example.vectorsearch.persistence.DatabaseFailureClassifier.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class DatabaseFailureClassifierTest {
    @Test
    void retryRequiresConfirmedRollbackRatherThanJustTransientException() {
        var conflict = new CannotAcquireLockException("test lock conflict");
        assertThat(DatabaseFailureClassifier.classify(conflict, ROLLED_BACK)).isEqualTo(TRANSIENT_ROLLED_BACK);
        assertThat(DatabaseFailureClassifier.classify(conflict, UNKNOWN)).isEqualTo(OUTCOME_UNKNOWN);
        assertThat(DatabaseFailureClassifier.classify(conflict, COMMITTED)).isEqualTo(OTHER);
    }

    @Test
    void integrityAndUnavailableFailuresAreNotInterchangeable() {
        assertThat(DatabaseFailureClassifier.classify(new DataIntegrityViolationException("check"), ROLLED_BACK))
                .isEqualTo(INTEGRITY);
        assertThat(DatabaseFailureClassifier.classify(new DataAccessResourceFailureException("offline"), NOT_STARTED))
                .isEqualTo(UNAVAILABLE);
        assertThat(DatabaseFailureClassifier.classify(new DataAccessResourceFailureException("commit?"), UNKNOWN))
                .isEqualTo(OUTCOME_UNKNOWN);
    }
}
