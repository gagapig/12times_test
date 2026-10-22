package com.example.vectorsearch.task;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.stereotype.Component;

/** Shared single-instance eligibility gate; P4 search will use the read side. */
@Component
public class VisibilityGate {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    public ReentrantReadWriteLock.ReadLock read() { return lock.readLock(); }
    public ReentrantReadWriteLock.WriteLock write() { return lock.writeLock(); }
}
