package com.example.vectorsearch.persistence;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

/** Policy vocabulary only in P1: no automatic retries or lifecycle changes here. */
public final class DatabaseFailureClassifier {
    public enum Outcome { NOT_STARTED, ROLLED_BACK, COMMITTED, UNKNOWN }
    public enum Kind { TRANSIENT_ROLLED_BACK, UNAVAILABLE, INTEGRITY, OUTCOME_UNKNOWN, OTHER }

    private DatabaseFailureClassifier() { }

    public static Kind classify(Throwable failure, Outcome outcome) {
        // Exception class alone cannot prove that a write was rolled back.
        if (outcome == Outcome.UNKNOWN) {
            return Kind.OUTCOME_UNKNOWN;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataIntegrityViolationException) {
                return Kind.INTEGRITY;
            }
            if (cause instanceof DataAccessResourceFailureException) {
                return Kind.UNAVAILABLE;
            }
            if (cause instanceof TransientDataAccessException && outcome == Outcome.ROLLED_BACK) {
                return Kind.TRANSIENT_ROLLED_BACK;
            }
        }
        return Kind.OTHER;
    }
}
