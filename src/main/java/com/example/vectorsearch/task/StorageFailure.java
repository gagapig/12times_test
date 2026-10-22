package com.example.vectorsearch.task;

import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Outcome;

public class StorageFailure extends RuntimeException {
    private final Kind kind;
    private final Outcome outcome;

    public StorageFailure(Kind kind, Outcome outcome, Throwable cause) {
        super("Storage operation failed: " + kind, cause);
        this.kind = kind;
        this.outcome = outcome;
    }

    public Kind kind() { return kind; }
    public Outcome outcome() { return outcome; }
}
