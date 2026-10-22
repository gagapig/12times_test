package com.example.vectorsearch.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "search")
public class SearchSettings {
    private int maxConcurrent = 4;
    private int candidateBatchSize = 256;
    private Duration budget = Duration.ofSeconds(10);
    private Duration gateWait = Duration.ofSeconds(2);

    public void validate() {
        if (maxConcurrent < 1 || maxConcurrent > 32 || candidateBatchSize < 1
                || budget.isNegative() || budget.isZero() || gateWait.isNegative() || gateWait.isZero()) {
            throw new IllegalArgumentException("Invalid search capacity or duration setting");
        }
    }

    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int value) { maxConcurrent = value; }
    public int getCandidateBatchSize() { return candidateBatchSize; }
    public void setCandidateBatchSize(int value) { candidateBatchSize = value; }
    public Duration getBudget() { return budget; }
    public void setBudget(Duration value) { budget = value; }
    public Duration getGateWait() { return gateWait; }
    public void setGateWait(Duration value) { gateWait = value; }
}
