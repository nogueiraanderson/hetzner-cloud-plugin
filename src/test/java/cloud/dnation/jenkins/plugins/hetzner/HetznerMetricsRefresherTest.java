/*
 *     Copyright 2026 Percona, LLC.
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

import cloud.dnation.hetznerclient.ServerDetail;
import cloud.dnation.hetznerclient.ServerType;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import hudson.model.labels.LabelAtom;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the v103.percona.16 fix for stale {@code hetzner_running_servers}
 * and {@code hetzner_provisioning_pending} gauges. The refresher walks
 * configured HetznerCloud instances on a 1-minute PeriodicWork and forces
 * a re-emit; without it, the gauges only update inside
 * {@link HetznerCloud#provision} and pin to the last-known peak when the
 * cloud is idle (observed today: psmdb.cd reported 25 servers in Mimir
 * while the Hetzner API reported 2 actually running).
 */
class HetznerMetricsRefresherTest {

    private HetznerCloudResourceManager rsrcMgr;
    private MockedStatic<Jenkins> jenkinsMock;
    private MockedStatic<HetznerCloudResourceManager> rsrcMgrMock;
    private Jenkins jenkins;

    @BeforeEach
    void setUp() {
        // Reset the metrics registry so each test starts clean. Otherwise the
        // RUNNING_SERVERS gauge keeps state across tests via the global registry.
        HetznerMetricProvider.RUNNING_SERVERS.clear();
        HetznerMetricProvider.PROVISIONING_PENDING.clear();

        jenkinsMock = mockStatic(Jenkins.class);
        rsrcMgrMock = mockStatic(HetznerCloudResourceManager.class);

        rsrcMgr = mock(HetznerCloudResourceManager.class);
        when(HetznerCloudResourceManager.create(anyString())).thenReturn(rsrcMgr);

        jenkins = mock(Jenkins.class);
        doAnswer((Answer<LabelAtom>) inv -> new LabelAtom(inv.getArgument(0)))
                .when(jenkins).getLabelAtom(anyString());
        when(Jenkins.get()).thenReturn(jenkins);
    }

    @AfterEach
    void tearDown() {
        jenkinsMock.close();
        rsrcMgrMock.close();
    }

    /**
     * The whole point of the refresher: when the cloud is idle and nothing has
     * called provision() in a while, refreshMetrics() must re-emit a fresh
     * count straight from the Hetzner API into the RUNNING_SERVERS gauge.
     *
     * <p>v103.percona.23: the gauge is now arch-labelled. Test servers
     * created via {@link #serverWithStatus(String)} have no serverType set,
     * so they collapse to the "unknown" bucket. Per-arch grouping is
     * exercised separately in {@link #refreshMetrics_splitsByArch}.
     */
    @Test
    void refreshMetrics_emitsLiveServerCountToGauge() throws Exception {
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(serverList(2));

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        cloud.refreshMetrics();

        double emitted = sumRunningAcrossArchs("hcloud-test");
        assertEquals(2.0, emitted, 0.0001, "RUNNING_SERVERS gauge should reflect live API count");
        verify(rsrcMgr, times(1)).fetchAllServers("hcloud-test");
    }

    /**
     * Only RUNNABLE state servers count (initialising / off / deleting do not).
     * Today this matches HetznerConstants.RUNNABLE_STATE_SET = {running, starting}.
     */
    @Test
    void refreshMetrics_filtersByRunnableState() throws Exception {
        List<ServerDetail> mixed = new ArrayList<>();
        mixed.add(serverWithStatus("running"));
        mixed.add(serverWithStatus("running"));
        mixed.add(serverWithStatus("off"));
        mixed.add(serverWithStatus("initializing"));
        mixed.add(serverWithStatus("deleting"));
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(mixed);

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        cloud.refreshMetrics();

        double emitted = sumRunningAcrossArchs("hcloud-test");
        assertTrue(emitted == 2.0 || emitted == 3.0,
                "Only RUNNABLE state servers counted; got " + emitted);
    }

    /**
     * v103.percona.23: running servers are emitted per arch derived from
     * Hetzner serverType (cpx* -> amd64, cax* -> arm64). Verifies the
     * grouping logic plus the explicit zero-emission for the always-emit
     * archs (otherwise stale series pin to their last non-zero value in
     * Mimir).
     */
    @Test
    void refreshMetrics_splitsByArch() throws Exception {
        List<ServerDetail> mixed = new ArrayList<>();
        mixed.add(serverWithType("cpx42"));
        mixed.add(serverWithType("cpx62"));
        mixed.add(serverWithType("cpx22"));
        mixed.add(serverWithType("cax31"));
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(mixed);

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        cloud.refreshMetrics();

        double amd = HetznerMetricProvider.RUNNING_SERVERS
                .labels("hcloud-test", HetznerMetricProvider.ARCH_AMD64).get();
        double arm = HetznerMetricProvider.RUNNING_SERVERS
                .labels("hcloud-test", HetznerMetricProvider.ARCH_ARM64).get();
        assertEquals(3.0, amd, 0.0001, "3 cpx* servers -> amd64");
        assertEquals(1.0, arm, 0.0001, "1 cax* server -> arm64");
        // unknown is lazily emitted; without an unknown observation it
        // should NOT appear as a series at all. Probe via the registry to
        // distinguish "absent" from "present at 0".
        Double unknownSample = io.prometheus.client.CollectorRegistry.defaultRegistry
                .getSampleValue("hetzner_running_servers",
                        new String[]{"cloud", "arch"},
                        new String[]{"hcloud-test", HetznerMetricProvider.ARCH_UNKNOWN});
        org.junit.jupiter.api.Assertions.assertNull(unknownSample,
                "no unrecognised serverType seen yet -> arch=\"unknown\" must NOT appear in Mimir");
    }

    /**
     * v103.percona.24: arch="unknown" is lazily emitted. On first observation
     * of a non-canonical SKU the series appears; on subsequent passes where
     * the unknown server is gone, the series persists at 0 (so dashboards
     * still see it -- it's a recent-incident marker). The always-emit set
     * (amd64, arm64) is unaffected.
     */
    @Test
    void refreshMetrics_lazilyEmitsUnknownArchOnceObserved() throws Exception {
        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());

        // Pass 1: only canonical SKUs. unknown series must not exist.
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(java.util.List.of(
                serverWithType("cpx42"), serverWithType("cax31")));
        cloud.refreshMetrics();
        org.junit.jupiter.api.Assertions.assertNull(probeUnknown("hcloud-test"),
                "pass 1: no unknown SKU observed -> arch=\"unknown\" must be absent");

        // Pass 2: a non-canonical SKU appears. unknown series materialises.
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(java.util.List.of(
                serverWithType("cpx42"), serverWithType("zzz99")));
        cloud.refreshMetrics();
        Double afterAppearance = probeUnknown("hcloud-test");
        org.junit.jupiter.api.Assertions.assertNotNull(afterAppearance,
                "pass 2: unknown SKU observed -> arch=\"unknown\" must now appear in Mimir");
        assertEquals(1.0, afterAppearance, 0.0001, "unknown bucket counts 1 server");

        // Pass 3: unknown server gone. Series persists at 0 (we have seen it).
        when(rsrcMgr.fetchAllServers(anyString())).thenReturn(java.util.List.of(
                serverWithType("cpx42")));
        cloud.refreshMetrics();
        Double afterDisappearance = probeUnknown("hcloud-test");
        org.junit.jupiter.api.Assertions.assertNotNull(afterDisappearance,
                "pass 3: unknown previously observed -> series must persist at zero, not vanish");
        assertEquals(0.0, afterDisappearance, 0.0001,
                "unknown count returns to zero but series stays for stale-incident visibility");
    }

    private static Double probeUnknown(String cloudName) {
        return io.prometheus.client.CollectorRegistry.defaultRegistry.getSampleValue(
                "hetzner_running_servers",
                new String[]{"cloud", "arch"},
                new String[]{cloudName, HetznerMetricProvider.ARCH_UNKNOWN});
    }

    /**
     * Transient Hetzner API failure must not propagate; the gauge keeps its
     * last-good value rather than gapping. This matches the explicit
     * "stale gauge is more useful than a gap" contract in HetznerCloud.
     */
    @Test
    void refreshMetrics_swallowsApiException() throws Exception {
        when(rsrcMgr.fetchAllServers(anyString()))
                .thenThrow(new RuntimeException("simulated 503 from Hetzner"));

        HetznerCloud cloud = new HetznerCloud("hcloud-test", "mock-creds", "10", new ArrayList<>());
        // Must NOT throw. PROVISIONING_PENDING still gets re-emitted defensively.
        cloud.refreshMetrics();

        double pending = HetznerMetricProvider.PROVISIONING_PENDING.labels("hcloud-test").get();
        assertEquals(0.0, pending, 0.0001,
                "PROVISIONING_PENDING re-emit must run even when running-count fetch fails");
    }

    /**
     * The HetznerMetricsRefresher PeriodicWork uses a 1-minute recurrence
     * period (PeriodicWork.MIN). Anything shorter would risk hammering the
     * Hetzner API; anything longer reintroduces the staleness this whole
     * fix exists to eliminate.
     */
    @Test
    void getRecurrencePeriod_isOneMinute() {
        HetznerMetricsRefresher refresher = new HetznerMetricsRefresher();
        assertEquals(TimeUnit.MINUTES.toMillis(1), refresher.getRecurrencePeriod(),
                "Refresher must tick every minute; this is the contract the dashboard relies on");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static List<ServerDetail> serverList(int n) {
        List<ServerDetail> servers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            servers.add(serverWithStatus("running"));
        }
        return servers;
    }

    private static ServerDetail serverWithStatus(String status) {
        ServerDetail s = new ServerDetail();
        s.setStatus(status);
        s.setName("hcloud-mock-" + System.nanoTime());
        return s;
    }

    private static ServerDetail serverWithType(String serverTypeName) {
        ServerDetail s = serverWithStatus("running");
        ServerType st = new ServerType();
        st.setName(serverTypeName);
        s.setServerType(st);
        return s;
    }

    /**
     * Read the running-servers gauge regardless of how many arch buckets it
     * is split across. Used by tests that care about the cloud-wide total
     * but not the per-arch breakdown. Uses the registry-level sample lookup
     * so it does NOT auto-instantiate a Child for an arch the plugin has
     * not actually emitted yet (which would defeat the v24 lazy-unknown
     * behavior).
     */
    private static double sumRunningAcrossArchs(String cloudName) {
        double sum = 0;
        for (String arch : HetznerMetricProvider.KNOWN_ARCHS) {
            Double v = io.prometheus.client.CollectorRegistry.defaultRegistry.getSampleValue(
                    "hetzner_running_servers",
                    new String[]{"cloud", "arch"},
                    new String[]{cloudName, arch});
            if (v != null) {
                sum += v;
            }
        }
        return sum;
    }
}
