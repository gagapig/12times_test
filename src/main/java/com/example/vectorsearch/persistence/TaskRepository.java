package com.example.vectorsearch.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface TaskRepository extends Repository<VectorTask, UUID> {
    Optional<VectorTask> findById(UUID taskId);
    Optional<VectorTask> findByDocumentId(String documentId);
    long count();
}
