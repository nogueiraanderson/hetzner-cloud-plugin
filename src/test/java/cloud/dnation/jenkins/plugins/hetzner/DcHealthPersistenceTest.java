/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Tests for DC breaker XmlFile persistence (PS-11173, v103.percona.21).
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.XmlFile;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WithJenkins
class DcHealthPersistenceTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        DcHealthTracker.resetAll();
        HetznerMetricProvider.resetForTest();
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        if (xml.exists() && !xml.delete()) {
            throw new IllegalStateException("Could not delete leftover " + xml);
        }
    }

    @AfterEach
    void tearDown() {
        DcHealthTracker.resetAll();
    }

    /**
     * On a FAILURE_THRESHOLD-driven OPEN transition, the persistence layer
     * must produce hetzner-dc-health.xml on disk. Save is deferred via
     * Timer, so we poll with a bounded timeout.
     */
    @Test
    void savesOnFailure() {
        // Trip the breaker (FAILURE_THRESHOLD = 2).
        DcHealthTracker.recordFailure("fsn1", "amd64");
        DcHealthTracker.recordFailure("fsn1", "amd64");
        assertFalse(DcHealthTracker.isHealthy("fsn1", "amd64"), "fsn1 should be OPEN after 2 failures");

        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        await().atMost(10, TimeUnit.SECONDS).until(xml::exists);
        assertTrue(xml.length() > 0, "xml file should not be empty");

        await().atMost(5, TimeUnit.SECONDS).until(
                () -> HetznerMetricProvider.DC_HEALTH_SAVES.get() >= 1);
        assertEquals(0.0, HetznerMetricProvider.DC_HEALTH_SAVE_FAILURES.get(),
                "no save failures expected on the happy path");
    }

    /**
     * A persisted OPEN breaker must be restored on init. Tests the
     * full save -> load round-trip end to end.
     */
    @Test
    void loadsOnInit() {
        // Phase 1: trip and persist
        DcHealthTracker.recordFailure("nbg1", "amd64");
        DcHealthTracker.recordFailure("nbg1", "amd64");
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        await().atMost(10, TimeUnit.SECONDS).until(xml::exists);

        // Phase 2: clear in-memory state (simulating a JVM restart at the
        // in-process layer) and call load() explicitly. Tests the
        // deserializer + afterLoad gauge restoration. JenkinsRule cannot
        // actually bounce the JVM mid-test, but DcHealthTracker.load() is
        // package-private and idempotent enough to invoke directly.
        DcHealthTracker.resetAll();
        assertTrue(DcHealthTracker.getAllBreakers().isEmpty());
        DcHealthTracker.load();

        // Phase 3: assert restored
        assertEquals(1, DcHealthTracker.getAllBreakers().size());
        DcCircuitBreaker restored = DcHealthTracker.getBreaker("nbg1", "amd64");
        assertNotNull(restored);
        assertEquals(DcCircuitBreaker.State.OPEN, restored.getState());
        assertEquals(2, restored.getConsecutiveFailures());
    }

    /**
     * An OPEN breaker whose last failure is older than the 30-min TTL
     * must load as CLOSED. This prevents a transient incident from
     * pinning a DC out of rotation after a long master restart.
     *
     * Implementation: write a hand-crafted XmlFile with an artificially
     * old openedAt, then call load(); state should be CLOSED.
     */
    @Test
    void ttl_resetsStaleOpen() throws Exception {
        // Trip a breaker via the public API so the XML file exists with
        // the right XStream class registrations, then mutate openedAt
        // backwards via reflection to simulate "this breaker has been
        // OPEN for longer than the TTL".
        DcHealthTracker.recordFailure("hel1", "amd64");
        DcHealthTracker.recordFailure("hel1", "amd64");
        DcCircuitBreaker breaker = DcHealthTracker.getBreaker("hel1", "amd64");
        assertEquals(DcCircuitBreaker.State.OPEN, breaker.getState());

        java.lang.reflect.Field openedAt = DcCircuitBreaker.class.getDeclaredField("openedAt");
        openedAt.setAccessible(true);
        long pastFortyMinutesAgo = System.currentTimeMillis() - (40L * 60 * 1000);
        openedAt.set(breaker, pastFortyMinutesAgo);

        // Manually save the mutated state.
        DcHealthTracker.save();
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        await().atMost(10, TimeUnit.SECONDS).until(() -> xml.exists() && xml.length() > 0);
        // Give the deferred Timer task a moment to flush our mutated state
        // (save() coalesces, so the disk file may still be the un-mutated
        // version). Force a fresh save by toggling state.
        Thread.sleep(500);
        DcHealthTracker.resetAll();
        // Re-trip so we definitely have a fresh file with the mutated
        // openedAt by going through the load path next.
        // (Simpler approach: write the XML directly via the same XmlFile
        // mechanism the production code uses.)
        DcHealthTracker.getBreaker("hel1", "amd64");
        DcCircuitBreaker freshBreaker = DcHealthTracker.getBreaker("hel1", "amd64");
        // Recreate "OPEN with stale openedAt" precisely via reflection.
        java.lang.reflect.Field stateField = DcCircuitBreaker.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(freshBreaker, DcCircuitBreaker.State.OPEN);
        openedAt.set(freshBreaker, pastFortyMinutesAgo);
        java.lang.reflect.Field consecutive = DcCircuitBreaker.class.getDeclaredField("consecutiveFailures");
        consecutive.setAccessible(true);
        consecutive.set(freshBreaker, 5);

        // Persist via the same Store path the production code uses.
        XmlFile xf = new XmlFile(Jenkins.XSTREAM2, xml);
        DcHealthTracker.Store store = new DcHealthTracker.Store(DcHealthTracker.getAllBreakers());
        xf.write(store);

        // Load. afterLoad() should detect the stale OPEN and reset to CLOSED.
        DcHealthTracker.resetAll();
        DcHealthTracker.load();

        DcCircuitBreaker loaded = DcHealthTracker.getBreaker("hel1", "amd64");
        assertEquals(DcCircuitBreaker.State.CLOSED, loaded.getState(),
                "stale OPEN should load as CLOSED");
        assertEquals(0, loaded.getConsecutiveFailures(),
                "consecutiveFailures should reset on stale-OPEN load");
    }

    /**
     * v103.percona.25 migration: a pre-v25 XML where the breaker map was
     * keyed solely by location (no {@code :arch} suffix) must load as
     * TWO per-arch breakers cloning the legacy state, so the loaded
     * state still gates both arches' templates for one compatibility
     * window. The legacy entry must NOT survive in the map (it would
     * shadow lookups for the new composite keys).
     */
    @Test
    void legacyKeyMigrationProducesPerArchBreakers() throws Exception {
        // Hand-craft a pre-v25 Store: one location-only key "fsn1" -> OPEN
        // with 4 consecutive failures and a recent openedAt (NOT stale).
        DcHealthTracker.resetAll();
        // Construct with a placeholder arch (the constructor's labels() call
        // rejects null arch). We then null out arch via reflection to mirror
        // the on-disk shape of a pre-v25 breaker, where the field did not
        // exist and XStream deserializes it as null.
        DcCircuitBreaker legacy = new DcCircuitBreaker("fsn1", "amd64");
        java.lang.reflect.Field stateField = DcCircuitBreaker.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(legacy, DcCircuitBreaker.State.OPEN);
        java.lang.reflect.Field cfField = DcCircuitBreaker.class.getDeclaredField("consecutiveFailures");
        cfField.setAccessible(true);
        cfField.setInt(legacy, 4);
        java.lang.reflect.Field openedAtField = DcCircuitBreaker.class.getDeclaredField("openedAt");
        openedAtField.setAccessible(true);
        openedAtField.setLong(legacy, System.currentTimeMillis() - 30_000L); // 30s ago, well within TTL

        // Pre-v25 lacked the arch field on the breaker object itself; null
        // it after construction so the persisted XML matches that shape.
        java.lang.reflect.Field archField = DcCircuitBreaker.class.getDeclaredField("arch");
        archField.setAccessible(true);
        archField.set(legacy, null);

        java.util.concurrent.ConcurrentHashMap<String, DcCircuitBreaker> seed = new java.util.concurrent.ConcurrentHashMap<>();
        seed.put("fsn1", legacy);    // legacy key shape: just the location
        DcHealthTracker.Store store = new DcHealthTracker.Store(seed);

        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        XmlFile xf = new XmlFile(Jenkins.XSTREAM2, xml);
        xf.write(store);

        // Now drive load() and verify the migration path fires.
        DcHealthTracker.resetAll();
        HetznerMetricProvider.resetForTest();
        DcHealthTracker.load();

        // The legacy "fsn1" key is gone; two arch-keyed breakers exist.
        assertEquals(2, DcHealthTracker.getAllBreakers().size(),
                "legacy single-key should expand into two per-arch breakers");
        DcCircuitBreaker amd = DcHealthTracker.getBreaker("fsn1", "amd64");
        DcCircuitBreaker arm = DcHealthTracker.getBreaker("fsn1", "arm64");
        assertNotNull(amd);
        assertNotNull(arm);
        assertEquals(DcCircuitBreaker.State.OPEN, amd.getState(),
                "migrated amd64 breaker should inherit OPEN state from legacy");
        assertEquals(DcCircuitBreaker.State.OPEN, arm.getState(),
                "migrated arm64 breaker should inherit OPEN state from legacy");
        assertEquals(4, amd.getConsecutiveFailures(),
                "migrated amd64 breaker should inherit consecutiveFailures from legacy");
        assertEquals(4, arm.getConsecutiveFailures(),
                "migrated arm64 breaker should inherit consecutiveFailures from legacy");
        assertEquals("amd64", amd.getArch());
        assertEquals("arm64", arm.getArch());

        // Migration counter ticked once per arch.
        assertEquals(1.0, HetznerMetricProvider.DC_HEALTH_LEGACY_KEYS_MIGRATED
                .labels("fsn1", "amd64").get());
        assertEquals(1.0, HetznerMetricProvider.DC_HEALTH_LEGACY_KEYS_MIGRATED
                .labels("fsn1", "arm64").get());
    }

    /**
     * Missing XML file is a silent no-op: registry stays empty, no
     * exception, no log error at WARN level. Critical for first-ever
     * deploy / clean install / downgrade scenarios.
     */
    @Test
    void missingFile_isNoOp() {
        File xml = new File(j.jenkins.getRootDir(), "hetzner-dc-health.xml");
        assertFalse(xml.exists(), "fixture should have no xml file");

        DcHealthTracker.resetAll();
        DcHealthTracker.load();

        assertTrue(DcHealthTracker.getAllBreakers().isEmpty(),
                "missing file must leave registry empty");
        assertFalse(xml.exists(),
                "load() must not create the file as a side effect");
    }
}
