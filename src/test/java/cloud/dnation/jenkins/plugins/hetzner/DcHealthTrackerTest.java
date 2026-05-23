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

import com.google.common.collect.Lists;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DcHealthTrackerTest {

    // Test templates default to cpx32 in makeTemplate(); archOf("cpx32") -> "amd64",
    // so most legacy tests are implicitly amd64-flavoured.
    private static final String AMD64 = "amd64";
    private static final String ARM64 = "arm64";

    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrMock;

    @BeforeEach
    void setUp() {
        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrMock = mockStatic(HetznerCloudResourceManager.class);

        Jenkins jenkins = mock(Jenkins.class);
        doAnswer(inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        // checkPermission is void; no-op by default on mock
        when(Jenkins.get()).thenReturn(jenkins);

        HetznerCloudResourceManager mgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(mgr);

        DcHealthTracker.resetAll();
    }

    @AfterEach
    void tearDown() {
        DcHealthTracker.resetAll();
        jenkinsMock.close();
        rsrcMgrMock.close();
    }

    @Test
    void registryCreatesBreakersOnDemand() {
        assertTrue(DcHealthTracker.getAllBreakers().isEmpty());
        DcCircuitBreaker cb = DcHealthTracker.getBreaker("fsn1", AMD64);
        assertNotNull(cb);
        assertEquals("fsn1", cb.getLocation());
        assertEquals(AMD64, cb.getArch());
        assertEquals(1, DcHealthTracker.getAllBreakers().size());
    }

    @Test
    void sameBreakerReturnedForSameLocationAndArch() {
        DcCircuitBreaker cb1 = DcHealthTracker.getBreaker("nbg1", AMD64);
        DcCircuitBreaker cb2 = DcHealthTracker.getBreaker("nbg1", AMD64);
        assertTrue(cb1 == cb2, "Same object reference expected");
    }

    @Test
    void differentLocationsGetDifferentBreakers() {
        DcCircuitBreaker fsn = DcHealthTracker.getBreaker("fsn1", AMD64);
        DcCircuitBreaker nbg = DcHealthTracker.getBreaker("nbg1", AMD64);
        assertFalse(fsn == nbg);
    }

    @Test
    void differentArchesGetDifferentBreakersForSameLocation() {
        DcCircuitBreaker fsnAmd = DcHealthTracker.getBreaker("fsn1", AMD64);
        DcCircuitBreaker fsnArm = DcHealthTracker.getBreaker("fsn1", ARM64);
        assertFalse(fsnAmd == fsnArm, "Same DC, different arch must yield distinct breakers");
        assertEquals(AMD64, fsnAmd.getArch());
        assertEquals(ARM64, fsnArm.getArch());
        assertEquals(2, DcHealthTracker.getAllBreakers().size());
    }

    @Test
    void resetAllClearsRegistry() {
        DcHealthTracker.getBreaker("fsn1", AMD64);
        DcHealthTracker.getBreaker("nbg1", AMD64);
        assertEquals(2, DcHealthTracker.getAllBreakers().size());
        DcHealthTracker.resetAll();
        assertTrue(DcHealthTracker.getAllBreakers().isEmpty());
    }

    @Test
    void sortByHealthPutsHealthyFirst() {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");
        HetznerServerTemplate t3 = makeTemplate("t3", "hel1");

        // Make fsn1+amd64 unhealthy (templates above are all cpx32 -> amd64)
        DcHealthTracker.recordFailure("fsn1", AMD64);
        DcHealthTracker.recordFailure("fsn1", AMD64);

        List<HetznerServerTemplate> ranked = DcHealthTracker.sortByHealth(
                Lists.newArrayList(t1, t2, t3));

        assertEquals(3, ranked.size());
        // t1 (fsn1, unhealthy) should be last
        assertEquals("fsn1", ranked.get(ranked.size() - 1).getLocation());
        // First two should be nbg1 or hel1
        Set<String> healthyLocations = new HashSet<>();
        healthyLocations.add(ranked.get(0).getLocation());
        healthyLocations.add(ranked.get(1).getLocation());
        assertTrue(healthyLocations.contains("nbg1"));
        assertTrue(healthyLocations.contains("hel1"));
    }

    @Test
    void sortByHealthAllHealthyShuffles() {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        // Run multiple times; all should succeed (no crash) and contain both
        for (int i = 0; i < 10; i++) {
            List<HetznerServerTemplate> ranked = DcHealthTracker.sortByHealth(
                    Lists.newArrayList(t1, t2));
            assertEquals(2, ranked.size());
            Set<String> locations = new HashSet<>();
            locations.add(ranked.get(0).getLocation());
            locations.add(ranked.get(1).getLocation());
            assertTrue(locations.contains("fsn1"));
            assertTrue(locations.contains("nbg1"));
        }
    }

    @Test
    void sortByHealthAllUnhealthyStillReturnsAll() {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        DcHealthTracker.recordFailure("fsn1", AMD64);
        DcHealthTracker.recordFailure("fsn1", AMD64);
        DcHealthTracker.recordFailure("nbg1", AMD64);
        DcHealthTracker.recordFailure("nbg1", AMD64);

        List<HetznerServerTemplate> ranked = DcHealthTracker.sortByHealth(
                Lists.newArrayList(t1, t2));
        assertEquals(2, ranked.size());
    }

    @Test
    void sortByHealthHandlesEdgeCases() {
        // null
        List<HetznerServerTemplate> result = DcHealthTracker.sortByHealth(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());

        // empty
        result = DcHealthTracker.sortByHealth(Collections.emptyList());
        assertTrue(result.isEmpty());

        // single
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        result = DcHealthTracker.sortByHealth(Collections.singletonList(t1));
        assertEquals(1, result.size());
        assertEquals("fsn1", result.get(0).getLocation());
    }

    @Test
    void dcIndependence() {
        // Failing fsn1 amd64 should not affect nbg1 amd64
        DcHealthTracker.recordFailure("fsn1", AMD64);
        DcHealthTracker.recordFailure("fsn1", AMD64);
        assertFalse(DcHealthTracker.isHealthy("fsn1", AMD64));
        assertTrue(DcHealthTracker.isHealthy("nbg1", AMD64));
    }

    /**
     * v25 invariant: ARM failures in a DC must not trip the AMD64 breaker
     * for the same DC. This is the core motivation for moving from
     * location-only to (location, arch) keying.
     */
    @Test
    void archIndependenceWithinSameDc() {
        // Two consecutive ARM failures in fsn1 trip the (fsn1, arm64) breaker
        DcHealthTracker.recordFailure("fsn1", ARM64);
        DcHealthTracker.recordFailure("fsn1", ARM64);

        // ARM side: tripped
        assertFalse(DcHealthTracker.isHealthy("fsn1", ARM64),
                "ARM breaker in fsn1 should be OPEN after 2 failures");
        // AMD64 side: untouched
        assertTrue(DcHealthTracker.isHealthy("fsn1", AMD64),
                "AMD64 breaker in fsn1 must NOT be affected by ARM failures");

        // And vice versa: AMD64 failures must not affect ARM
        DcHealthTracker.recordSuccess("fsn1", ARM64); // reset ARM
        assertTrue(DcHealthTracker.isHealthy("fsn1", ARM64));
        DcHealthTracker.recordFailure("fsn1", AMD64);
        DcHealthTracker.recordFailure("fsn1", AMD64);
        assertFalse(DcHealthTracker.isHealthy("fsn1", AMD64));
        assertTrue(DcHealthTracker.isHealthy("fsn1", ARM64));
    }

    /**
     * v25 invariant: sortByHealth derives arch per template via
     * archOf(serverType) so an ARM template in a DC with an OPEN ARM
     * breaker is deprioritized while an AMD64 template in the same DC
     * stays in the healthy partition.
     */
    @Test
    void sortByHealthIsArchScoped() {
        // Two templates in fsn1: one ARM (cax21), one AMD64 (cpx32)
        HetznerServerTemplate arm = new HetznerServerTemplate("arm-fsn", "armLbl",
                "img1", "fsn1", "cax21");
        HetznerServerTemplate amd = new HetznerServerTemplate("amd-fsn", "amdLbl",
                "img1", "fsn1", "cpx32");

        // Trip ARM in fsn1
        DcHealthTracker.recordFailure("fsn1", ARM64);
        DcHealthTracker.recordFailure("fsn1", ARM64);

        List<HetznerServerTemplate> ranked = DcHealthTracker.sortByHealth(
                Lists.newArrayList(arm, amd));
        // AMD64 in fsn1 stays healthy; ARM in fsn1 is unhealthy and should sort last
        assertEquals(2, ranked.size());
        assertEquals("amd-fsn", ranked.get(0).getName(),
                "Healthy AMD64 fsn1 template should rank ahead of unhealthy ARM fsn1");
        assertEquals("arm-fsn", ranked.get(1).getName());
    }

    /**
     * v103.percona.26: filterHealthy drops templates whose (location, arch)
     * breaker is OPEN. This is the entry-point storm gate used by
     * HetznerCloud.provision() to short-circuit the API hammering observed
     * during the 2026-05-22 cax arm64 shortage incident.
     */
    @Test
    void filterHealthyDropsOpenBreakers() {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");  // amd64
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");  // amd64
        HetznerServerTemplate t3 = makeTemplate("t3", "hel1");  // amd64

        // Open fsn1+amd64 only; nbg1 and hel1 stay healthy
        DcHealthTracker.recordFailure("fsn1", AMD64);
        DcHealthTracker.recordFailure("fsn1", AMD64);

        List<HetznerServerTemplate> healthy = DcHealthTracker.filterHealthy(
                Lists.newArrayList(t1, t2, t3));
        assertEquals(2, healthy.size());
        // t1 (fsn1, OPEN) is filtered out; t2 and t3 remain
        Set<String> names = new HashSet<>();
        names.add(healthy.get(0).getName());
        names.add(healthy.get(1).getName());
        assertTrue(names.contains("t2"));
        assertTrue(names.contains("t3"));
        assertFalse(names.contains("t1"), "unhealthy template must be filtered out");
    }

    /**
     * v103.percona.26: when every matching DC is OPEN, filterHealthy
     * returns an empty list. HetznerCloud.provision() uses this to gate
     * the API call entirely.
     */
    @Test
    void filterHealthyAllUnhealthyReturnsEmpty() {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");
        HetznerServerTemplate t3 = makeTemplate("t3", "hel1");

        // All three amd64 DCs open
        for (String dc : new String[]{"fsn1", "nbg1", "hel1"}) {
            DcHealthTracker.recordFailure(dc, AMD64);
            DcHealthTracker.recordFailure(dc, AMD64);
        }

        List<HetznerServerTemplate> healthy = DcHealthTracker.filterHealthy(
                Lists.newArrayList(t1, t2, t3));
        assertTrue(healthy.isEmpty(),
                "all DCs OPEN must return empty list (storm-gate invariant)");
    }

    /**
     * v103.percona.26: filterHealthy is arch-scoped, so an ARM-only outage
     * in a DC does not affect AMD64 templates in the same DC. Mirrors the
     * v25 archIndependenceWithinSameDc invariant.
     */
    @Test
    void filterHealthyIsArchScoped() {
        HetznerServerTemplate arm = new HetznerServerTemplate("arm-fsn", "armLbl",
                "img1", "fsn1", "cax21");
        HetznerServerTemplate amd = new HetznerServerTemplate("amd-fsn", "amdLbl",
                "img1", "fsn1", "cpx32");

        // Trip ARM in fsn1 only
        DcHealthTracker.recordFailure("fsn1", ARM64);
        DcHealthTracker.recordFailure("fsn1", ARM64);

        List<HetznerServerTemplate> healthy = DcHealthTracker.filterHealthy(
                Lists.newArrayList(arm, amd));
        assertEquals(1, healthy.size());
        assertEquals("amd-fsn", healthy.get(0).getName(),
                "AMD64 template must remain when only ARM in same DC is OPEN");
    }

    /**
     * v103.percona.26: templates whose serverType maps to arch="unknown"
     * (non-canonical Hetzner SKU prefix) get a distinct breaker
     * (location, "unknown"). If that breaker is OPEN, filterHealthy must
     * drop the template. Pins the v23/v24 lazy-emit arch path.
     */
    @Test
    void filterHealthyHandlesUnknownArch() {
        HetznerServerTemplate unknown = new HetznerServerTemplate("unk-fsn", "lbl",
                "img1", "fsn1", "zzz99");  // SKU prefix that archOf() maps to "unknown"
        HetznerServerTemplate amd = makeTemplate("amd-fsn", "fsn1");

        // Open the (fsn1, unknown) breaker explicitly
        DcHealthTracker.recordFailure("fsn1", "unknown");
        DcHealthTracker.recordFailure("fsn1", "unknown");

        List<HetznerServerTemplate> healthy = DcHealthTracker.filterHealthy(
                Lists.newArrayList(unknown, amd));
        assertEquals(1, healthy.size());
        assertEquals("amd-fsn", healthy.get(0).getName(),
                "unknown-arch template must be filtered when its (loc, unknown) breaker is OPEN");
    }

    /**
     * v103.percona.26: filterHealthy handles null and empty input
     * gracefully (returns empty list, never throws).
     */
    @Test
    void filterHealthyHandlesEdgeCases() {
        assertTrue(DcHealthTracker.filterHealthy(null).isEmpty(),
                "null input -> empty list");
        assertTrue(DcHealthTracker.filterHealthy(Collections.emptyList()).isEmpty(),
                "empty input -> empty list");

        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        List<HetznerServerTemplate> single = DcHealthTracker.filterHealthy(
                Collections.singletonList(t1));
        assertEquals(1, single.size(), "single healthy template -> single-element list");
    }

    private HetznerServerTemplate makeTemplate(String name, String location) {
        return new HetznerServerTemplate(name, "label1", "img1", location, "cpx32");
    }
}
