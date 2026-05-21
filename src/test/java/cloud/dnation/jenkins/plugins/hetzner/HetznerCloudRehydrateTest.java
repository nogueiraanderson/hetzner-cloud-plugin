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

import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * v103.percona.25: regression tests for the transient-field rehydration
 * path in {@link HetznerCloud}. XStream's {@code Unsafe.allocateInstance}
 * bypasses field initializers on deserialize, so both {@code seenArchExtras}
 * and {@code pendingProvisions} can be null on a freshly loaded instance
 * until {@code readResolve()} has run. This test reflectively nulls both
 * fields and verifies the helpers + readResolve restore them without NPE.
 */
class HetznerCloudRehydrateTest {

    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrMock;

    @BeforeEach
    void setUp() {
        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrMock = mockStatic(HetznerCloudResourceManager.class);

        Jenkins jenkins = mock(Jenkins.class);
        when(Jenkins.get()).thenReturn(jenkins);

        HetznerCloudResourceManager mgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(mgr);
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
        rsrcMgrMock.close();
    }

    @Test
    void readResolveRehydratesBothTransientFields() throws Exception {
        HetznerCloud cloud = newCloud();

        // Simulate XStream Unsafe.allocateInstance by nulling both transients.
        setField(cloud, "seenArchExtras", null);
        setField(cloud, "pendingProvisions", null);
        assertFieldNull(cloud, "seenArchExtras");
        assertFieldNull(cloud, "pendingProvisions");

        // readResolve() must repopulate both fields.
        invokeReadResolve(cloud);

        Object extras = getField(cloud, "seenArchExtras");
        Object pending = getField(cloud, "pendingProvisions");
        assertNotNull(extras, "seenArchExtras must be non-null after readResolve()");
        assertNotNull(pending, "pendingProvisions must be non-null after readResolve()");
        assertEquals(0, ((AtomicInteger) pending).get(),
                "pendingProvisions must rehydrate to 0");
        assertFalse(((Set<?>) extras).iterator().hasNext(),
                "seenArchExtras must rehydrate empty");
    }

    @Test
    void readResolveIsIdempotent() throws Exception {
        HetznerCloud cloud = newCloud();

        Object originalExtras = getField(cloud, "seenArchExtras");
        Object originalPending = getField(cloud, "pendingProvisions");
        // Mutate the AtomicInteger so we can verify idempotency preserves state.
        ((AtomicInteger) originalPending).set(7);

        // Re-invoking readResolve() must NOT replace already-populated fields.
        invokeReadResolve(cloud);

        assertEquals(originalExtras, getField(cloud, "seenArchExtras"),
                "readResolve() should not replace an already-populated seenArchExtras");
        assertEquals(7, ((AtomicInteger) getField(cloud, "pendingProvisions")).get(),
                "readResolve() should preserve the existing pendingProvisions value");
    }

    @Test
    void ensureSeenArchExtrasLazyInitialisesOnAccess() throws Exception {
        HetznerCloud cloud = newCloud();
        setField(cloud, "seenArchExtras", null);

        // Invoke the lazy guard via reflection.
        Method m = HetznerCloud.class.getDeclaredMethod("ensureSeenArchExtras");
        m.setAccessible(true);
        Object set = m.invoke(cloud);

        assertNotNull(set, "ensureSeenArchExtras() must never return null");
        assertNotNull(getField(cloud, "seenArchExtras"),
                "ensureSeenArchExtras() must populate the field as a side-effect");
    }

    @Test
    void ensurePendingProvisionsLazyInitialisesOnAccess() throws Exception {
        HetznerCloud cloud = newCloud();
        setField(cloud, "pendingProvisions", null);

        Method m = HetznerCloud.class.getDeclaredMethod("ensurePendingProvisions");
        m.setAccessible(true);
        AtomicInteger counter = (AtomicInteger) m.invoke(cloud);

        assertNotNull(counter, "ensurePendingProvisions() must never return null");
        assertEquals(0, counter.get(),
                "Freshly rehydrated pendingProvisions must start at 0");
        assertNotNull(getField(cloud, "pendingProvisions"),
                "ensurePendingProvisions() must populate the field as a side-effect");
    }

    private static HetznerCloud newCloud() {
        return new HetznerCloud("test-cloud", "creds-id", "10", Collections.emptyList());
    }

    private static void invokeReadResolve(HetznerCloud cloud) throws Exception {
        Method m = HetznerCloud.class.getDeclaredMethod("readResolve");
        m.setAccessible(true);
        m.invoke(cloud);
    }

    private static Object getField(Object obj, String name) throws Exception {
        Field f = HetznerCloud.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = HetznerCloud.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static void assertFieldNull(Object obj, String name) throws Exception {
        Object v = getField(obj, name);
        if (v != null) {
            throw new AssertionError("Expected " + name + " to be null but was " + v);
        }
    }
}
