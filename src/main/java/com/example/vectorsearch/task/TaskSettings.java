package com.example.vectorsearch.task;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "task")
public class TaskSettings {
    private boolean enabled = true;
    private int workerCount = 2;
    private int queueCapacity = 1000;
    private int maxPendingTasks = 10000;
    private int dispatchBatchSize = 256;
    private int maxAttempts = 3;
    private int maxDbAttempts = 3;
    private Duration dispatchInterval = Duration.ofSeconds(1);
    private Duration gateWait = Duration.ofSeconds(2);
    private Duration shutdownGrace = Duration.ofSeconds(30);

    public void validate() {
        if (workerCount < 1 || workerCount > 32 || queueCapacity < 1 || maxPendingTasks < 1
                || dispatchBatchSize < 1 || maxAttempts < 1 || maxDbAttempts < 1
                || dispatchInterval.isNegative() || dispatchInterval.isZero()
                || gateWait.isNegative() || gateWait.isZero()
                || shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("Invalid task capacity or duration setting");
        }
    }

    public int getWorkerCount() { return workerCount; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public void setWorkerCount(int value) { workerCount = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getMaxPendingTasks() { return maxPendingTasks; }
    public void setMaxPendingTasks(int value) { maxPendingTasks = value; }
    public int getDispatchBatchSize() { return dispatchBatchSize; }
    public void setDispatchBatchSize(int value) { dispatchBatchSize = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public int getMaxDbAttempts() { return maxDbAttempts; }
    public void setMaxDbAttempts(int value) { maxDbAttempts = value; }
    public Duration getDispatchInterval() { return dispatchInterval; }
    public void setDispatchInterval(Duration value) { dispatchInterval = value; }
    public Duration getGateWait() { return gateWait; }
    public void setGateWait(Duration value) { gateWait = value; }
    public Duration getShutdownGrace() { return shutdownGrace; }
    public void setShutdownGrace(Duration value) { shutdownGrace = value; }
}
