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

import cloud.dnation.jenkins.plugins.hetzner.launcher.AbstractHetznerSshConnector;
import com.google.common.collect.Lists;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeCallableRetryTest {

    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrMock;
    private MockedStatic<HetznerApiClient> apiClientMock;
    private HetznerCloudResourceManager mgr;

    @BeforeEach
    void setUp() {
        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrMock = mockStatic(HetznerCloudResourceManager.class);
        apiClientMock = mockStatic(HetznerApiClient.class);

        Jenkins jenkins = mock(Jenkins.class);
        doAnswer(inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);

        mgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(mgr);

        // Mock HetznerApiClient for rate-limit tests
        HetznerApiClient mockApiClient = mock(HetznerApiClient.class);
        when(mockApiClient.getRemaining()).thenReturn(0);
        when(mockApiClient.timeUntilReset()).thenReturn(java.time.Duration.ofSeconds(60));
        when(HetznerApiClient.forCredentials(anyString())).thenReturn(mockApiClient);

        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
    }

    @AfterEach
    void tearDown() {
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
        apiClientMock.close();
        jenkinsMock.close();
        rsrcMgrMock.close();
    }

    @Test
    void retryOnResourceUnavailable() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        AbstractHetznerSshConnector connector = mock(AbstractHetznerSshConnector.class);
        t1.setConnector(connector);
        t2.setConnector(connector);

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // First call (fsn1) throws resource_unavailable
        when(mgr.createServer(any())).thenThrow(
                new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        // Should fail because second DC also fails (mock always throws)
        assertThrows(HetznerProvisioningException.class, callable::call);

        // fsn1 should have a failure recorded
        assertFalse(DcHealthTracker.isHealthy("fsn1") && DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures() == 0,
                "fsn1 should have at least one failure recorded");
    }

    @Test
    void noRetryOnAuthError() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Auth error: should NOT retry
        when(mgr.createServer(any())).thenThrow(
                new HetznerProvisioningException("Unauthorized", 401, "unauthorized", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertEquals(401, ex.getHttpStatus());
        // fsn1 failure recorded, but nbg1 should NOT have been tried
        assertEquals(1, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
    }

    @Test
    void allDcsFailThrowsLast() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Both DCs fail with resource_unavailable
        when(mgr.createServer(any()))
                .thenThrow(new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"))
                .thenThrow(new HetznerProvisioningException("DC full", 422, "resource_unavailable", "nbg1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        // Last exception should be from nbg1
        assertEquals("nbg1", ex.getLocation());
        // Both should have failures
        assertTrue(DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures() >= 1);
        assertTrue(DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures() >= 1);
    }

    @Test
    void singleTemplateNoRetry() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        when(mgr.createServer(any())).thenThrow(
                new HetznerProvisioningException("DC full", 422, "resource_unavailable", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        assertThrows(HetznerProvisioningException.class, callable::call);
        assertEquals(1, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
    }

    @Test
    void rateLimitedAbortsImmediately() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Rate-limit error: should abort immediately, no DC failover
        when(mgr.createServer(any())).thenThrow(
                new HetznerProvisioningException("Rate limited", 429, "rate_limit_exceeded", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertTrue(ex.isRateLimited());
        // Rate limit throws BEFORE recordFailure, so neither DC should have failures
        assertEquals(0, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
        // Verify only ONE provisioning attempt was made (no DC failover)
        verify(mgr, times(1)).createServer(any());
    }

    @Test
    void configErrorAbortsImmediately() throws Exception {
        HetznerServerTemplate t1 = makeTemplate("t1", "fsn1");
        HetznerServerTemplate t2 = makeTemplate("t2", "nbg1");

        HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-cred", "10",
                Lists.newArrayList(t1, t2));

        HetznerServerAgent agent = mock(HetznerServerAgent.class);
        when(agent.getTemplate()).thenReturn(t1);
        when(agent.getComputer()).thenReturn(null);

        // Config error: should abort immediately, no DC failover
        when(mgr.createServer(any())).thenThrow(
                new HetznerProvisioningException("Invalid image", 422, "invalid_input", "fsn1"));

        List<HetznerServerTemplate> ranked = List.of(t1, t2);
        NodeCallable callable = new NodeCallable(agent, cloud, ranked);

        HetznerProvisioningException ex = assertThrows(HetznerProvisioningException.class, callable::call);
        assertTrue(ex.isConfigError());
        // Config error throws BEFORE recordFailure, so neither DC should have failures
        assertEquals(0, DcHealthTracker.getBreaker("fsn1").getConsecutiveFailures());
        assertEquals(0, DcHealthTracker.getBreaker("nbg1").getConsecutiveFailures());
        // Verify only ONE provisioning attempt was made (no DC failover)
        verify(mgr, times(1)).createServer(any());
    }

    private HetznerServerTemplate makeTemplate(String name, String location) {
        HetznerServerTemplate t = new HetznerServerTemplate(name, "label1", "img1", location, "cpx32");
        t.setConnector(mock(AbstractHetznerSshConnector.class));
        return t;
    }
}
