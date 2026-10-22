package com.example.vectorsearch.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Deliberately omits save/merge: later mutations must update specific columns. */
public interface DocumentRepository extends Repository<DocumentRecord, String> {
    Optional<DocumentRecord> findById(String documentId);
    boolean existsById(String documentId);
    long count();
}
