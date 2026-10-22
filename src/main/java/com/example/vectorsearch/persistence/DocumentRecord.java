package com.example.vectorsearch.persistence;

import jakarta.persistence.*;
import java.time.Instant;

/** Field access only. No public setters or whole-entity update API. */
@Entity
@Table(name = "document_record")
public class DocumentRecord {
    @Id
    @Column(name = "document_id", length = 64, nullable = false, updatable = false)
    private String documentId;

    @Lob
    @Column(name = "original_text", nullable = false, updatable = false)
    private String originalText;

    @Column(name = "channel", length = 64, nullable = false, updatable = false)
    private String channel;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "vector_completed_at")
    private Instant vectorCompletedAt;

    @Column(name = "vector_ready", nullable = false)
    private boolean vectorReady;

    @Column(name = "invalidated", nullable = false)
    private boolean invalidated;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "vector_bytes", length = 1024)
    private byte[] vectorBytes;

    @Column(name = "vector_version", length = 32)
    private String vectorVersion;

    protected DocumentRecord() { }

    public DocumentRecord(String documentId, String originalText, String channel, Instant submittedAt) {
        this.documentId = documentId;
        this.originalText = originalText;
        this.channel = channel;
        this.submittedAt = submittedAt;
    }

    public String getDocumentId() { return documentId; }
    public String getOriginalText() { return originalText; }
    public String getChannel() { return channel; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getVectorCompletedAt() { return vectorCompletedAt; }
    public boolean isVectorReady() { return vectorReady; }
    public boolean isInvalidated() { return invalidated; }
    public Instant getInvalidatedAt() { return invalidatedAt; }
    public long getHitCount() { return hitCount; }
    public byte[] getVectorBytes() { return vectorBytes == null ? null : vectorBytes.clone(); }
    public String getVectorVersion() { return vectorVersion; }
}
