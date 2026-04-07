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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HetznerApiClientTest {

    @BeforeEach
    void setUp() {
        HetznerApiClient.resetAll();
    }

    @AfterEach
    void tearDown() {
        HetznerApiClient.resetAll();
    }

    @Test
    void forCredentials_sameInstanceForSameId() {
        HetznerApiClient a = HetznerApiClient.forCredentials("cred-A");
        HetznerApiClient b = HetznerApiClient.forCredentials("cred-A");
        assertSame(a, b);
    }

    @Test
    void forCredentials_differentInstanceForDifferentId() {
        HetznerApiClient a = HetznerApiClient.forCredentials("cred-A");
        HetznerApiClient b = HetznerApiClient.forCredentials("cred-B");
        assertNotSame(a, b);
    }

    @Test
    void isRateLimited_falseWhenNotBlocked() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        assertFalse(client.isRateLimited());
    }

    @Test
    void isRateLimited_trueWhenBlocked() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        client.recordRateLimit(60);
        assertTrue(client.isRateLimited());
    }

    @Test
    void isRateLimited_clearsAfterExpiry() throws Exception {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        // Set blockedUntil to the past via reflection
        setBlockedUntil(client, Instant.now().minusSeconds(1));
        assertFalse(client.isRateLimited());
        // Verify it was atomically cleared
        assertNull_blockedUntil(client);
    }

    @Test
    void recordRateLimit_neverShortens() throws Exception {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        Instant before120 = Instant.now();
        client.recordRateLimit(120);
        Instant after120 = getBlockedUntil(client);
        assertNotNull(after120);
        // Verify 120s was actually used (not always-60s)
        assertTrue(after120.isAfter(before120.plusSeconds(100)),
                "blockedUntil should be ~120s from now, got " + Duration.between(before120, after120));

        client.recordRateLimit(30);
        Instant afterShort = getBlockedUntil(client);
        // Should not have been shortened
        assertFalse(afterShort.isBefore(after120),
                "blockedUntil should not shorten from 120s to 30s");
    }

    @Test
    void recordRateLimit_advances() throws Exception {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        Instant before30 = Instant.now();
        client.recordRateLimit(30);
        Instant after30 = getBlockedUntil(client);
        // Verify 30s was actually used
        long delta30 = Duration.between(before30, after30).toSeconds();
        assertTrue(delta30 >= 25 && delta30 <= 35,
                "Expected ~30s, got " + delta30 + "s");

        Instant before120 = Instant.now();
        client.recordRateLimit(120);
        Instant after120 = getBlockedUntil(client);
        assertTrue(after120.isAfter(after30),
                "blockedUntil should advance from 30s to 120s");
        // Verify 120s was actually used
        assertTrue(after120.isAfter(before120.plusSeconds(100)),
                "blockedUntil should be ~120s from now after advance");
    }

    @Test
    void recordRateLimit_defaultsSixtySeconds() throws Exception {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        Instant before = Instant.now();
        client.recordRateLimit(0);
        Instant until = getBlockedUntil(client);
        assertNotNull(until);
        // Should be ~60s from now (allow 5s tolerance)
        long deltaSeconds = Duration.between(before, until).toSeconds();
        assertTrue(deltaSeconds >= 55 && deltaSeconds <= 65,
                "Expected ~60s, got " + deltaSeconds + "s");
    }

    @Test
    void timeUntilReset_zeroDurationWhenNotBlocked() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        assertEquals(Duration.ZERO, client.timeUntilReset());
    }

    @Test
    void timeUntilReset_positiveDurationWhenBlocked() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        client.recordRateLimit(60);
        Duration d = client.timeUntilReset();
        assertTrue(d.toSeconds() > 0, "Expected positive duration, got " + d);
    }

    @Test
    void updateRateLimitState_tracksRemaining() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        assertEquals(Integer.MAX_VALUE, client.getRemaining());
        client.updateRateLimitState(3600, 100);
        assertEquals(100, client.getRemaining());
    }

    @Test
    void updateRateLimitState_ignoresNegativeValues() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        client.updateRateLimitState(3600, 500);
        assertEquals(500, client.getRemaining());
        // Negative remaining should not update
        client.updateRateLimitState(-1, -1);
        assertEquals(500, client.getRemaining());
    }

    @Test
    void invalidate_clearsAndRemovesFromCache() {
        HetznerApiClient first = HetznerApiClient.forCredentials("test-inv");
        first.invalidate();
        HetznerApiClient second = HetznerApiClient.forCredentials("test-inv");
        assertNotSame(first, second, "After invalidate, a new instance should be created");
    }

    @Test
    void getRemaining_startsAtMaxValue() {
        HetznerApiClient client = HetznerApiClient.forCredentials("test");
        assertEquals(Integer.MAX_VALUE, client.getRemaining());
    }

    @Test
    void concurrentRateLimitRecording() throws Exception {
        HetznerApiClient client = HetznerApiClient.forCredentials("concurrent-test");
        int threadCount = 10;
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        // Max duration any thread will write: 30 + 9*10 + 99 = 219s
        long maxDurationSeconds = 30 + (threadCount - 1) * 10 + (iterations - 1);
        Instant beforeAll = Instant.now();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < iterations; i++) {
                        long duration = 30 + (threadId * 10) + i;
                        client.recordRateLimit(duration);
                        client.isRateLimited();
                        client.timeUntilReset();
                        client.getRemaining();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get(), "No exceptions expected during concurrent access");
        assertTrue(client.isRateLimited(), "Should still be rate-limited after concurrent writes");

        // Verify blockedUntil converged to at least the maximum duration
        Instant finalBlockedUntil = getBlockedUntil(client);
        assertNotNull(finalBlockedUntil);
        long actualSeconds = Duration.between(beforeAll, finalBlockedUntil).toSeconds();
        assertTrue(actualSeconds >= maxDurationSeconds - 5,
                "blockedUntil should converge to max (~" + maxDurationSeconds
                        + "s), got " + actualSeconds + "s");
    }

    // --- Reflection helpers (same pattern as DcCircuitBreakerTest) ---

    @SuppressWarnings("unchecked")
    private static Instant getBlockedUntil(HetznerApiClient client) throws Exception {
        Field f = HetznerApiClient.class.getDeclaredField("blockedUntil");
        f.setAccessible(true);
        AtomicReference<Instant> ref = (AtomicReference<Instant>) f.get(client);
        return ref.get();
    }

    @SuppressWarnings("unchecked")
    private static void setBlockedUntil(HetznerApiClient client, Instant value) throws Exception {
        Field f = HetznerApiClient.class.getDeclaredField("blockedUntil");
        f.setAccessible(true);
        AtomicReference<Instant> ref = (AtomicReference<Instant>) f.get(client);
        ref.set(value);
    }

    private static void assertNull_blockedUntil(HetznerApiClient client) throws Exception {
        Instant value = getBlockedUntil(client);
        assertTrue(value == null, "Expected blockedUntil to be null after expiry, got " + value);
    }
}
