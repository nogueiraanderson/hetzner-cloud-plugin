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
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void singleFailureStaysClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
        assertEquals(1, cb.getConsecutiveFailures());
    }

    @Test
    void twoConsecutiveFailuresOpensCircuit() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquireProbe());
        assertEquals(2, cb.getConsecutiveFailures());
    }

    @Test
    void successResetsFailureCount() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void successAfterOpenResetsToClosed() {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        // Simulate half-open transition and success
        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.tryAcquireProbe());
    }

    @Test
    void openTransitionsToHalfOpenAfterTimeout() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());

        // Use reflection to set openedAt to the past
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.tryAcquireProbe());
    }

    @Test
    void halfOpenFailureReopensCircuit() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();

        // Force HALF_OPEN by backdating openedAt
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.tryAcquireProbe()); // transitions to HALF_OPEN

        cb.recordFailure();
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.tryAcquireProbe());
    }

    @Test
    void halfOpenSuccessCloses() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();

        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.tryAcquireProbe()); // HALF_OPEN

        cb.recordSuccess();
        assertEquals(DcCircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    /**
     * v103.percona.26: after the breaker enters HALF_OPEN (OPEN reset-timeout
     * elapsed), only the FIRST caller gets the probe lease. Subsequent
     * concurrent callers see false until recordSuccess() closes the breaker
     * or recordFailure() reopens it. Closes the storm path where N queued
     * shards all saw HALF_OPEN as healthy and stampeded the Hetzner API.
     */
    @Test
    void halfOpenIsSingleProbe() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertTrue(cb.tryAcquireProbe(),
                "first caller in HALF_OPEN window holds the probe lease");
        assertFalse(cb.tryAcquireProbe(),
                "second concurrent caller is denied (lease already taken)");
        assertFalse(cb.tryAcquireProbe(),
                "third caller still denied (lease not released until success/failure)");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    /**
     * v103.percona.26: a fresh probe lease is armed each time the breaker
     * transitions OPEN -> HALF_OPEN. After a probe failure reopens the
     * breaker and another reset-timeout window elapses, the next caller
     * should again hold the lease.
     */
    @Test
    void halfOpenLeaseRearmsAfterReopening() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertTrue(cb.tryAcquireProbe(), "first probe lease acquired");
        cb.recordFailure(); // probe-holder fails -> back to OPEN
        assertEquals(DcCircuitBreaker.State.OPEN, cb.getState());

        // Advance clock past another reset window; a new lease should be armed.
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);
        assertTrue(cb.tryAcquireProbe(),
                "after reopen + timeout, a fresh probe lease is available");
        assertFalse(cb.tryAcquireProbe(),
                "second caller in the new HALF_OPEN window denied");
    }

    /**
     * v103.percona.26: isProbeable() is non-consuming. Two consecutive
     * calls in a HALF_OPEN window both return true (the lease is not
     * consumed by a peek). Critical for filterHealthy/sortByHealth which
     * call into the breaker as part of list filtering; consuming the
     * lease there would steal it from the actual provisioner.
     */
    @Test
    void isProbeableIsNonConsuming() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        // Two peeks in a row both succeed (lease not consumed)
        assertTrue(cb.isProbeable(), "first peek sees HALF_OPEN as healthy");
        assertTrue(cb.isProbeable(), "second peek still sees HALF_OPEN healthy (lease intact)");

        // Now the actual consumer takes the lease
        assertTrue(cb.tryAcquireProbe(), "consumer acquires the lease");
        // And the next consumer is denied
        assertFalse(cb.tryAcquireProbe(), "second consumer denied");
        // But isProbeable() now correctly returns false too (lease taken)
        assertFalse(cb.isProbeable(), "peek after consumption returns false");
    }

    /**
     * v103.percona.26: if the probe-holder consumes the lease and then
     * dies without calling recordSuccess/recordFailure (thread crash,
     * non-DC bootstrap exception path that does not record), the breaker
     * is otherwise pinned in HALF_OPEN forever with no lease. After
     * HALF_OPEN_STALE_TTL_MS (2 * RESET_TIMEOUT_MS = 10 min) the lease
     * is re-armed on the next isProbeable()/tryAcquireProbe() call.
     */
    @Test
    void halfOpenLeaseReArmsAfterStaleTtl() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        assertTrue(cb.tryAcquireProbe(), "lease acquired");
        assertFalse(cb.tryAcquireProbe(), "lease taken, second caller denied");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());

        // Simulate the probe-holder dying: backdate halfOpenEnteredAt past
        // the stale TTL. We do this via reflection since the field is
        // private + transient.
        java.lang.reflect.Field f = DcCircuitBreaker.class.getDeclaredField("halfOpenEnteredAt");
        f.setAccessible(true);
        f.setLong(cb, System.currentTimeMillis() - (2L * 6 * 60 * 1000)); // 12 minutes ago

        // The stale lease should re-arm on the next call.
        assertTrue(cb.tryAcquireProbe(), "after stale TTL elapsed, fresh lease available");
        assertFalse(cb.tryAcquireProbe(), "lease consumed; subsequent caller denied");
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState(),
                "still in HALF_OPEN until next outcome recorded");
    }

    /**
     * v103.percona.26: getState() also lazily resets OPEN -> HALF_OPEN when
     * the timeout has elapsed. This path must also arm the probe lease so a
     * subsequent tryAcquireProbe() call can consume it; otherwise a getter
     * call would consume the implicit "first caller" semantics without
     * anyone actually being able to probe.
     */
    @Test
    void getStateLazyResetArmsProbeLease() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
        cb.recordFailure();
        cb.recordFailure();
        setOpenedAt(cb, System.currentTimeMillis() - DcCircuitBreaker.resetTimeoutMs() - 1);

        // getState() lazy-resets OPEN -> HALF_OPEN; lease should be armed.
        assertEquals(DcCircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.tryAcquireProbe(),
                "after getState() lazy-reset, the next isHealthy() holds the lease");
        assertFalse(cb.tryAcquireProbe(),
                "lease is single-use after lazy-reset path too");
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
        DcCircuitBreaker cb = new DcCircuitBreaker("nbg1", "amd64");
        assertEquals("nbg1", cb.getLocation());
    }

    @Test
    void concurrentFailuresAreThreadSafe() throws Exception {
        DcCircuitBreaker cb = new DcCircuitBreaker("hel1", "amd64");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger exceptions = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        cb.recordFailure();
                        cb.tryAcquireProbe();
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
        DcCircuitBreaker cb = new DcCircuitBreaker("fsn1", "amd64");
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
