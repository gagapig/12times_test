package com.example.vectorsearch.api;

import com.example.vectorsearch.validation.ValidChannel;
import com.example.vectorsearch.validation.ValidText;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Strict search request contract shared by the controller and validation tests. */
public class SearchRequest {
    @ValidText
    private String text;
    @NotNull @Min(1) @Max(100)
    private Integer topK;
    @ValidChannel(allowAbsent = true)
    private String channel;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public String getChannel() { return channel; }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setChannel(String channel) { this.channel = channel; }
}
