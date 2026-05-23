/*
 *     Copyright 2021 https://dnation.cloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.launcher.AbstractHetznerSshConnector;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import io.prometheus.client.CollectorRegistry;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
class HetznerCloudSimpleTest {

    private HetznerCloudResourceManager rsrcMgr;

    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> hetznerCloudResourceManagerMockedStatic;

    @BeforeEach
    void setUp() {
        jenkinsMock = mockStatic(Jenkins.class);
        hetznerCloudResourceManagerMockedStatic = mockStatic(HetznerCloudResourceManager.class);

        rsrcMgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(rsrcMgr);

        Jenkins jenkins = mock(Jenkins.class);
        doAnswer((Answer<LabelAtom>) invocationOnMock -> new LabelAtom(invocationOnMock.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);

        // v103.percona.26: tests in this class touch DcHealthTracker
        // and the metric counters; reset both so static state from
        // a prior test (or test-class order) cannot leak in.
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
        HetznerMetricProvider.resetForTest();
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
        hetznerCloudResourceManagerMockedStatic.close();
        DcHealthTracker.resetAll();
        TemplateErrorTracker.resetAll();
        HetznerMetricProvider.resetForTest();
    }

    @Test
    void testCanProvision() throws Exception {
        HetznerServerTemplate template1 = new HetznerServerTemplate("template-1", "java",
                "name=img1", "nbg1", "cx21");
        final AbstractHetznerSshConnector connector = mock(AbstractHetznerSshConnector.class);
        template1.setConnector(connector);

        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                Lists.newArrayList(template1));
        Cloud.CloudState cloudState = new Cloud.CloudState(new LabelAtom("java"), 1);
        assertTrue(cloud.canProvision(cloudState));

        final Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(cloudState, 1);
        final NodeProvisioner.PlannedNode node = Iterables.getOnlyElement(plannedNodes);
        await().atMost(30, TimeUnit.SECONDS).until(node.future::isDone);
        verify(connector, times(1)).createLauncher();
        verify(rsrcMgr, times(1)).fetchAllServers(anyString());

        Cloud.CloudState cloudState2 = new Cloud.CloudState(new LabelAtom("unknown"), 1);
        assertFalse(cloud.canProvision(cloudState2));
    }

    @Test
    void testCannotProvisionInExclusiveMode() {
        HetznerServerTemplate tmpl1 = new HetznerServerTemplate("tmpl1", "label1", "img1", "fsn1", "cx31");
        tmpl1.setMode(Node.Mode.EXCLUSIVE);
        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                Lists.newArrayList(tmpl1)
        );
        Cloud.CloudState cloudState = new Cloud.CloudState(new LabelAtom("java"), 1);
        assertFalse(cloud.canProvision(cloudState));
    }

    @Test
    void testCanProvisionInNormalMode() {
        HetznerServerTemplate tmpl1 = new HetznerServerTemplate("tmpl1", null, "img1", "fsn1", "cx31");
        tmpl1.setMode(Node.Mode.NORMAL);
        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                Lists.newArrayList(tmpl1)
        );
        Cloud.CloudState cloudState = new Cloud.CloudState(new LabelAtom("java"), 1);
        assertTrue(cloud.canProvision(cloudState));
    }

    //see https://github.com/jenkinsci/hetzner-cloud-plugin/issues/15
    @Test
    void testCanProvisionNullJobLabel() {
        HetznerServerTemplate tmpl1 = new HetznerServerTemplate("tmpl1", null, "img1", "fsn1", "cx31");
        tmpl1.setMode(Node.Mode.NORMAL);
        HetznerServerTemplate tmpl2 = new HetznerServerTemplate("tmpl1", "label2,label3", "img1", "fsn1", "cx31");
        tmpl2.setMode(Node.Mode.EXCLUSIVE);
        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                Lists.newArrayList(tmpl1, tmpl2)
        );
        Cloud.CloudState cloudState = new Cloud.CloudState(null, 1);
        assertTrue(cloud.canProvision(cloudState));
        Cloud.CloudState cloudState2 = new Cloud.CloudState(new LabelAtom("label3"), 1);
        assertTrue(cloud.canProvision(cloudState2));
    }

    /**
     * v103.percona.26: storm-gate test. When every matching template's
     * (location, arch) DC breaker is OPEN, provision() must return empty
     * AND must NOT call fetchAllServers / createServer. This is the
     * exact regression that drove the 2026-05-22 cax arm64 storm.
     */
    @Test
    void provisionSkipsWhenAllMatchingDcsUnhealthy() throws Exception {
        HetznerServerTemplate t1 = new HetznerServerTemplate("t1-fsn", "arm",
                "img1", "fsn1", "cax21");  // arm64
        HetznerServerTemplate t2 = new HetznerServerTemplate("t2-nbg", "arm",
                "img1", "nbg1", "cax21");
        HetznerServerTemplate t3 = new HetznerServerTemplate("t3-hel", "arm",
                "img1", "hel1", "cax21");
        final AbstractHetznerSshConnector connector = mock(AbstractHetznerSshConnector.class);
        t1.setConnector(connector);
        t2.setConnector(connector);
        t3.setConnector(connector);

        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                Lists.newArrayList(t1, t2, t3));

        // Open all three (location, arm64) breakers
        for (String dc : new String[]{"fsn1", "nbg1", "hel1"}) {
            DcHealthTracker.recordFailure(dc, "arm64");
            DcHealthTracker.recordFailure(dc, "arm64");
        }

        Cloud.CloudState state = new Cloud.CloudState(new LabelAtom("arm"), 1);
        Collection<NodeProvisioner.PlannedNode> planned = cloud.provision(state, 1);

        // No planned nodes
        assertTrue(planned.isEmpty(), "all-DC-breakers-open must produce no planned nodes");

        // The storm-gate invariant: NO API calls at all
        verify(rsrcMgr, never()).fetchAllServers(anyString());
        verify(rsrcMgr, never()).createServer(any(), any());
        verify(connector, never()).createLauncher();

        // PROVISION_SKIPPED{reason="no_healthy_dc"} == 1
        Double skipped = CollectorRegistry.defaultRegistry.getSampleValue(
                "hetzner_provision_skipped_total",
                new String[]{"cloud", "reason"},
                new String[]{"hcloud-01", HetznerMetricProvider.REASON_NO_HEALTHY_DC});
        assertNotNull(skipped, "skipped counter must appear with reason=no_healthy_dc");
        assertEquals(1.0, skipped, 0.0001);

        // Pending gauge stays at 0 (we never even incremented it)
        double pending = HetznerMetricProvider.PROVISIONING_PENDING.labels("hcloud-01").get();
        assertEquals(0.0, pending, 0.0001,
                "PROVISIONING_PENDING must not increment when we short-circuit");

        // No uncaught-exception path was hit
        Double uncaught = CollectorRegistry.defaultRegistry.getSampleValue(
                "hetzner_provision_uncaught_exceptions_total",
                new String[]{"cloud"}, new String[]{"hcloud-01"});
        assertNull(uncaught, "no uncaught path expected for the gate");
    }

    /**
     * v103.percona.26: positive case. With one DC healthy (fsn1) and the
     * other two (nbg1, hel1) OPEN, provision() must proceed using the
     * healthy template — the gate is not over-zealous.
     */
    @Test
    void provisionProceedsWhenOneHealthyDcExists() throws Exception {
        HetznerServerTemplate t1 = new HetznerServerTemplate("t1-fsn", "java",
                "img1", "fsn1", "cpx32");  // amd64 - will stay healthy
        HetznerServerTemplate t2 = new HetznerServerTemplate("t2-nbg", "java",
                "img1", "nbg1", "cpx32");
        HetznerServerTemplate t3 = new HetznerServerTemplate("t3-hel", "java",
                "img1", "hel1", "cpx32");
        final AbstractHetznerSshConnector connector = mock(AbstractHetznerSshConnector.class);
        t1.setConnector(connector);
        t2.setConnector(connector);
        t3.setConnector(connector);

        final HetznerCloud cloud = new HetznerCloud("hcloud-01", "mock-credentials", "10",
                Lists.newArrayList(t1, t2, t3));

        // Open nbg1 + hel1 but keep fsn1 healthy
        DcHealthTracker.recordFailure("nbg1", "amd64");
        DcHealthTracker.recordFailure("nbg1", "amd64");
        DcHealthTracker.recordFailure("hel1", "amd64");
        DcHealthTracker.recordFailure("hel1", "amd64");

        Cloud.CloudState state = new Cloud.CloudState(new LabelAtom("java"), 1);
        Collection<NodeProvisioner.PlannedNode> planned = cloud.provision(state, 1);

        // Provisioning proceeded: one PlannedNode + fetchAllServers was called
        NodeProvisioner.PlannedNode node = Iterables.getOnlyElement(planned);
        await().atMost(30, TimeUnit.SECONDS).until(node.future::isDone);
        verify(rsrcMgr, times(1)).fetchAllServers(anyString());

        // no_healthy_dc reason was NOT incremented
        Double skipped = CollectorRegistry.defaultRegistry.getSampleValue(
                "hetzner_provision_skipped_total",
                new String[]{"cloud", "reason"},
                new String[]{"hcloud-01", HetznerMetricProvider.REASON_NO_HEALTHY_DC});
        assertNull(skipped, "no_healthy_dc must NOT fire when one DC is healthy");
    }
}
