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

import java.util.ArrayList;
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
        DcCircuitBreaker cb = DcHealthTracker.getBreaker("fsn1");
        assertNotNull(cb);
        assertEquals("fsn1", cb.getLocation());
        assertEquals(1, DcHealthTracker.getAllBreakers().size());
    }

    @Test
    void sameBreakerReturnedForSameLocation() {
        DcCircuitBreaker cb1 = DcHealthTracker.getBreaker("nbg1");
        DcCircuitBreaker cb2 = DcHealthTracker.getBreaker("nbg1");
        assertTrue(cb1 == cb2, "Same object reference expected");
    }

    @Test
    void differentLocationsGetDifferentBreakers() {
        DcCircuitBreaker fsn = DcHealthTracker.getBreaker("fsn1");
        DcCircuitBreaker nbg = DcHealthTracker.getBreaker("nbg1");
        assertFalse(fsn == nbg);
    }

    @Test
    void resetAllClearsRegistry() {
        DcHealthTracker.getBreaker("fsn1");
        DcHealthTracker.getBreaker("nbg1");
        assertEquals(2, DcHealthTracker.getAllBreakers().size());
        DcHealthTracker.resetAll();
        assertTrue(DcHealthTracker.getAllBreakers().isEmpty());
    }

    @Test
    void sortByHealthPutsHealthyFirst() {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");
        HetznerServerTemplate t3 = makeTemplate("t3", "hel1");

        // Make fsn1 unhealthy
        DcHealthTracker.recordFailure("fsn1");
        DcHealthTracker.recordFailure("fsn1");

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

        DcHealthTracker.recordFailure("fsn1");
        DcHealthTracker.recordFailure("fsn1");
        DcHealthTracker.recordFailure("nbg1");
        DcHealthTracker.recordFailure("nbg1");

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
        // Failing fsn1 should not affect nbg1
        DcHealthTracker.recordFailure("fsn1");
        DcHealthTracker.recordFailure("fsn1");
        assertFalse(DcHealthTracker.isHealthy("fsn1"));
        assertTrue(DcHealthTracker.isHealthy("nbg1"));
    }

    private HetznerServerTemplate makeTemplate(String name, String location) {
        return new HetznerServerTemplate(name, "label1", "img1", location, "cpx32");
    }
}
