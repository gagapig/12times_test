package com.example.vectorsearch.task;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Outcome;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Each call owns one transaction. Commit uncertainty is retained, never guessed away. */
@Component
public class DatabaseOperation {
    private final PlatformTransactionManager manager;

    public DatabaseOperation(PlatformTransactionManager manager) { this.manager = manager; }

    public <T> T write(Supplier<T> work) { return execute(work, false, false); }
    public <T> T read(Supplier<T> work) { return execute(work, true, false); }
    public <T> T snapshotRead(Supplier<T> work) { return execute(work, true, true); }

    private <T> T execute(Supplier<T> work, boolean readOnly, boolean repeatableRead) {
        var template = new TransactionTemplate(manager);
        template.setReadOnly(readOnly);
        if (repeatableRead) {
            template.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
        }
        var outcome = new AtomicReference<>(Outcome.UNKNOWN);
        try {
            return template.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completion) {
                        outcome.set(switch (completion) {
                            case STATUS_COMMITTED -> Outcome.COMMITTED;
                            case STATUS_ROLLED_BACK -> Outcome.ROLLED_BACK;
                            default -> Outcome.UNKNOWN;
                        });
                    }
                });
                return work.get();
            });
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new StorageFailure(DatabaseFailureClassifier.classify(error, outcome.get()), outcome.get(), error);
        }
    }
}
