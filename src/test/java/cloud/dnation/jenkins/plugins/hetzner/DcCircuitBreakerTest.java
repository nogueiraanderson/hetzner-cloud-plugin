/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DcCircuitBreakerTest {

    @AfterEach
    void tearDown() {
        DcHealthTracker.resetAll();
    }

    @Test
    void newBreakerStartsClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isHealthy());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void singleFailureStaysClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isHealthy());
        assertEquals(1, cb.getConsecutiveFailures());
    }

    @Test
    void twoConsecutiveFailuresOpensCircuit() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.isHealthy());
        assertEquals(2, cb.getConsecutiveFailures());
    }

    @Test
    void successResetsFailureCount() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isHealthy());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void successAfterOpenResetsToClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        // Simulate half-open transition and success
        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isHealthy());
    }

    @Test
    void openTransitionsToHalfOpenAfterTimeout() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());

        // Use reflection to set openedAt to the past
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.isHealthy());
    }

    @Test
    void halfOpenFailureReopensCircuit() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        cb.recordFailure();

        // Force HALF_OPEN by backdating openedAt
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.isHealthy()); // transitions to HALF_OPEN

        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.isHealthy());
    }

    @Test
    void halfOpenSuccessCloses() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        cb.recordFailure();
        cb.recordFailure();

        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.isHealthy()); // HALF_OPEN

        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void failureThresholdIsTwo() {
        assertEquals(2, DcCircuitBreaker.failureThreshold());
    }

    @Test
    void resetTimeoutIsFiveMinutes() {
        assertEquals(5 * 60 * 1000, DcCircuitBreaker.resetTimeoutMs());
    }

    @Test
    void locationIsPreserved() {
        DcCircuitBreaker cb = new DcCircuitBreaker("nbg1");
        assertEquals("nbg1", cb.getLocation());
    }

    @Test
    void concurrentFailuresAreThreadSafe() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("hel1");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger exceptions = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        cb.recordFailure();
                        cb.isHealthy();
                        cb.recordSuccess();
                        cb.getState();
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, exceptions.get(), "No exceptions expected from concurrent access");
    }

    @Test
    void timestampsAreRecorded() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1");
        long before = System.currentTimeMillis();
        cb.recordSuccess();
        assertTrue(cb.getLastSuccessAt() >= before);

        before = System.currentTimeMillis();
        cb.recordFailure();
        assertTrue(cb.getLastFailureAt() >= before);
    }

    private static void setOpenedAt(DcCircuitBreaker cb, long value) throws Exception {
        Field f = DcCircuitBreaker.class.getDeclaredField("openedAt");
        f.setAccessible(true);
        f.set(cb, value);
    }
}
