package com.example.vectorsearch.api;

import com.example.vectorsearch.validation.ValidChannel;
import com.example.vectorsearch.validation.ValidText;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class SubmitDocumentRequest {
    // ASCII IDs are a project convention, not an examination requirement.
    @NotNull
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "documentId 须为 1–64 个英文字母、数字、下划线或连字符")
    private String documentId;
    @ValidText
    private String text;
    @ValidChannel
    private String channel = "default";

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
}
