package com.example.vectorsearch;

import com.example.vectorsearch.api.ApiException;
import com.example.vectorsearch.api.SubmitDocumentRequest;
import com.example.vectorsearch.api.TaskView;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Kind;
import com.example.vectorsearch.persistence.DatabaseFailureClassifier.Outcome;
import com.example.vectorsearch.persistence.TaskStatus;
import com.example.vectorsearch.task.*;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentTasksUnknownOutcomeTest {
    TaskStore store = mock(TaskStore.class);
    TaskEngine engine = mock(TaskEngine.class);
    DatabaseRetry retry = mock(DatabaseRetry.class);
    DocumentTasks service = new DocumentTasks(store, engine, retry, new TaskSettings(), new VisibilityGate());
    StorageFailure uncertain = new StorageFailure(Kind.OUTCOME_UNKNOWN, Outcome.UNKNOWN,
            new RuntimeException("uncertain commit"));

    @BeforeEach
    void setup() {
        when(retry.run(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        doThrow(uncertain).when(store).create(anyString(), anyString(), anyString(), any(), any(), anyInt());
    }

    @Test
    void aFreshReadThatProvesCommitReturnsTheOriginalTaskWithoutReplayingTheWrite() {
        when(store.task(any())).thenAnswer(call -> Optional.of(new TaskView(call.getArgument(0), "doc-1",
                TaskStatus.QUEUED, null, null, null, 0, null, null)));

        var accepted = service.submit(request());

        assertThat(accepted.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(accepted.documentId()).isEqualTo("doc-1");
        verify(store).task(accepted.taskId());
        verify(store, times(1)).create(anyString(), anyString(), anyString(), any(), any(), anyInt());
        verify(engine).signalDispatch();
        verify(engine, never()).degrade(any());
    }

    @Test
    void anUnresolvedCommitReturnsServiceUnavailableAndDoesNotReplayTheWrite() {
        when(store.task(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(request()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.code()).isEqualTo("STORAGE_OUTCOME_UNKNOWN");
                });
        verify(store, times(1)).create(anyString(), anyString(), anyString(), any(), any(), anyInt());
        verify(engine).degrade(uncertain);
        verify(engine, never()).signalDispatch();
    }

    private static SubmitDocumentRequest request() {
        var request = new SubmitDocumentRequest();
        request.setDocumentId("doc-1");
        request.setText("text");
        return request;
    }
}
