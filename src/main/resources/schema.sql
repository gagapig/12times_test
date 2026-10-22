-- Schema is authoritative; Hibernate only validates it.
CREATE TABLE IF NOT EXISTS document_record (
    document_id VARCHAR(64) PRIMARY KEY,
    original_text CLOB NOT NULL,
    channel VARCHAR(64) NOT NULL DEFAULT 'default',
    submitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    vector_completed_at TIMESTAMP WITH TIME ZONE,
    vector_ready BOOLEAN NOT NULL DEFAULT FALSE,
    invalidated BOOLEAN NOT NULL DEFAULT FALSE,
    invalidated_at TIMESTAMP WITH TIME ZONE,
    hit_count BIGINT NOT NULL DEFAULT 0 CHECK (hit_count >= 0),
    vector_bytes VARBINARY(1024),
    vector_version VARCHAR(32),
    CONSTRAINT ck_document_vector CHECK (
        (vector_ready = FALSE AND vector_bytes IS NULL
            AND vector_version IS NULL AND vector_completed_at IS NULL)
        OR
        (vector_ready = TRUE AND vector_bytes IS NOT NULL
            AND OCTET_LENGTH(vector_bytes) = 1024
            AND vector_version IS NOT NULL AND vector_completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_document_invalidated CHECK (
        (invalidated = FALSE AND invalidated_at IS NULL)
        OR (invalidated = TRUE AND invalidated_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS vector_task (
    task_id UUID PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (recovery_count >= 0),
    last_recovered_at TIMESTAMP WITH TIME ZONE,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    CONSTRAINT fk_task_document FOREIGN KEY (document_id)
        REFERENCES document_record(document_id),
    CONSTRAINT ck_task_status CHECK
        (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_task_terminal CHECK (
        (status IN ('QUEUED', 'RUNNING') AND finished_at IS NULL
            AND error_code IS NULL AND error_message IS NULL)
        OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL
            AND error_code IS NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL
            AND error_code IS NOT NULL AND error_message IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_task_dispatch
    ON vector_task(status, created_at, task_id);
CREATE INDEX IF NOT EXISTS idx_document_search
    ON document_record(vector_ready, invalidated, document_id);
CREATE INDEX IF NOT EXISTS idx_document_channel_search
    ON document_record(channel, vector_ready, invalidated, document_id);
CREATE INDEX IF NOT EXISTS idx_document_submitted
    ON document_record(submitted_at DESC, document_id);
