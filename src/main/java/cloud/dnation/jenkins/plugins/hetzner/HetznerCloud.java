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

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.google.common.primitives.Ints;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Objects;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.RandomStringUtils;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class HetznerCloud extends AbstractCloudImpl {
    @Getter
    private final String credentialsId;
    @Getter
    private List<HetznerServerTemplate> serverTemplates;
    private transient HetznerCloudResourceManager resourceManager;

    /**
     * Tracks in-flight provisioning requests that have passed the cap check
     * but not yet called createServer(). This closes the race window where
     * concurrent provision() calls or loop iterations all see the same stale
     * runningNodeCount() and over-provision beyond instanceCap.
     *
     * Incremented before submitting NodeCallable, decremented in its finally
     * block (success or failure). Included in the effective cap calculation.
     */
    // Non-final + lazy ensureXxx() guard at access sites: XStream's
    // Unsafe.allocateInstance bypasses the field initializer, so a freshly
    // deserialized HetznerCloud (e.g. after a plugin dynamic reload) can
    // briefly see this field as null before readResolve() runs. Belt-and-
    // suspenders: readResolve() also null-inits both transient fields.
    // See ensurePendingProvisions() and ensureSeenArchExtras().
    private transient AtomicInteger pendingProvisions = new AtomicInteger(0);

    @DataBoundConstructor
    public HetznerCloud(String name, String credentialsId, String instanceCapStr,
                        List<HetznerServerTemplate> serverTemplates) {
        super(name, instanceCapStr);
        this.credentialsId = credentialsId;
        this.serverTemplates = serverTemplates;
        readResolve();
    }

    /**
     * Rank templates by DC health: healthy DCs first, unhealthy last.
     * Within each partition, templates are shuffled randomly.
     * When all DCs are healthy (normal case), equivalent to random shuffle.
     *
     * @param matchingTemplates List of all matching templates.
     * @return ranked list (healthy first)
     */
    static List<HetznerServerTemplate> rankTemplatesByHealth(List<HetznerServerTemplate> matchingTemplates) {
        return DcHealthTracker.sortByHealth(matchingTemplates);
    }

    @DataBoundSetter
    public void setServerTemplates(List<HetznerServerTemplate> serverTemplates) {
      this.serverTemplates = Objects.requireNonNullElse(serverTemplates, Collections.emptyList());
        readResolve();
    }

    protected Object readResolve() {
        // Transient fields are not part of the persisted XML; XStream's
        // Unsafe.allocateInstance bypasses field initializers, so we
        // rehydrate them explicitly here. Access sites also lazy-guard via
        // ensureXxx() so any future path that bypasses readResolve() stays
        // safe.
        ensurePendingProvisions();
        ensureSeenArchExtras();
        resourceManager = HetznerCloudResourceManager.create(credentialsId);
        if (serverTemplates == null) {
            setServerTemplates(Collections.emptyList());
        }
        for (HetznerServerTemplate template : serverTemplates) {
            template.setCloud(this);
            template.readResolve();
        }
        publishConfigMetrics();
        return this;
    }

    /**
     * Emit info-style gauges describing the cloud's configuration. Called
     * from {@link #readResolve()} so panels render the topology even before
     * any provisioning happens.
     */
    private void publishConfigMetrics() {
        HetznerMetricProvider.CLOUD_INFO
                .labels(name, credentialsId != null ? credentialsId : "").set(1);
        HetznerMetricProvider.CLOUD_TEMPLATE_COUNT.labels(name).set(serverTemplates.size());
        HetznerMetricProvider.INSTANCE_CAP.labels(name).set(getInstanceCap());
        for (HetznerServerTemplate template : serverTemplates) {
            HetznerMetricProvider.TEMPLATE_INFO.labels(
                    name,
                    template.getName() != null ? template.getName() : "",
                    template.getImage() != null ? template.getImage() : "",
                    template.getServerType() != null ? template.getServerType() : "",
                    template.getLocation() != null ? template.getLocation() : ""
            ).set(1);
            HetznerMetricProvider.TEMPLATE_EXECUTORS.labels(
                    name, template.getName() != null ? template.getName() : ""
            ).set(template.getNumExecutors());
        }
    }

    public HetznerCloudResourceManager getResourceManager() {
        if (resourceManager == null) {
            resourceManager = HetznerCloudResourceManager.create(credentialsId);
        }
        return resourceManager;
    }

    /**
     * Arch label values this cloud has observed at least once on a non-empty
     * server set, beyond the always-emit set. The point is to make
     * {@code arch="unknown"} a lazy signal rather than constant background
     * noise: it never appears in Mimir until the plugin actually sees a
     * non-canonical Hetzner SKU, but once observed it keeps re-emitting
     * (zero if currently empty) so the series does not pin at its last
     * non-zero value. Cleared at JVM restart, which is the right cadence
     * (a strange-SKU incident two weeks ago is no longer relevant if the
     * fleet has been clean ever since).
     */
    private transient java.util.Set<String> seenArchExtras =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Lazy-init guard for {@link #pendingProvisions}. XStream's
     * Unsafe.allocateInstance bypasses the field initializer; readResolve()
     * also calls this so deserialized instances are safe by the time the
     * first provisioning / refresh tick runs. Idempotent.
     */
    private synchronized AtomicInteger ensurePendingProvisions() {
        if (pendingProvisions == null) {
            pendingProvisions = new AtomicInteger(0);
        }
        return pendingProvisions;
    }

    /**
     * Lazy-init guard for {@link #seenArchExtras}. Same Unsafe-bypass
     * rationale as {@link #ensurePendingProvisions()}. Idempotent.
     */
    private synchronized java.util.Set<String> ensureSeenArchExtras() {
        if (seenArchExtras == null) {
            seenArchExtras = java.util.concurrent.ConcurrentHashMap.newKeySet();
        }
        return seenArchExtras;
    }

    @SneakyThrows
    private int runningNodeCount() {
        // Note: if fetchAllServers() throws (rate-limit, network blip), the
        // gauge keeps its last-good value. That's intentional -- a stale
        // gauge for a few minutes during an outage is more useful than a
        // gap that breaks alerts. Persistent staleness is detectable as a
        // flat-line on the dashboard.
        //
        // v23+: group running VMs by arch (cpx*/cx*/ccx* -> amd64,
        // cax* -> arm64, anything else -> unknown). amd64 and arm64 are
        // always emitted (even at zero) so a bucket that drops from N to 0
        // doesn't pin at its last non-zero value in Mimir. "unknown" is
        // lazily emitted only after it has actually been observed at least
        // once on this cloud (see seenArchExtras), so dashboards stay quiet
        // until something is genuinely worth flagging.
        java.util.Map<String, Long> byArch = getResourceManager().fetchAllServers(name)
                .stream()
                .filter(sd -> HetznerConstants.RUNNABLE_STATE_SET.contains(sd.getStatus()))
                .collect(java.util.stream.Collectors.groupingBy(
                        sd -> HetznerMetricProvider.archOf(
                                sd.getServerType() != null ? sd.getServerType().getName() : null),
                        java.util.stream.Collectors.counting()));
        // Promote any newly observed non-canonical arch into the
        // seenArchExtras set so subsequent passes keep emitting it (at zero
        // or otherwise). Only non-zero observations matter; a strict zero
        // for an arch we've never seen would create the very noise we are
        // trying to avoid.
        java.util.Set<String> extras = ensureSeenArchExtras();
        byArch.forEach((arch, count) -> {
            if (count > 0 && !HetznerMetricProvider.ALWAYS_EMIT_ARCHS.contains(arch)) {
                extras.add(arch);
            }
        });
        int total = 0;
        for (String arch : HetznerMetricProvider.ALWAYS_EMIT_ARCHS) {
            long c = byArch.getOrDefault(arch, 0L);
            HetznerMetricProvider.RUNNING_SERVERS.labels(name, arch).set(c);
            total += Ints.checkedCast(c);
        }
        for (String arch : extras) {
            long c = byArch.getOrDefault(arch, 0L);
            HetznerMetricProvider.RUNNING_SERVERS.labels(name, arch).set(c);
            total += Ints.checkedCast(c);
        }
        return total;
    }

    /**
     * Effective running count including in-flight provisions not yet visible
     * in the Hetzner API. Prevents over-provisioning during burst demand.
     */
    private int effectiveNodeCount() {
        return runningNodeCount() + ensurePendingProvisions().get();
    }

    /**
     * Refresh the running and pending gauges from authoritative sources.
     * Called by {@link HetznerMetricsRefresher} on a periodic timer so the
     * gauges reflect reality even when the cloud is idle and no provisioning
     * has fired recently. Without this, {@code hetzner_running_servers}
     * stays pinned to the last-known value (last time {@code provision()}
     * called {@code runningNodeCount()}), which can be wildly stale (e.g.
     * 25 reported in Mimir while only 2 servers actually run).
     *
     * <p>Exception-swallowing matches {@code runningNodeCount}'s
     * {@code @SneakyThrows} contract: a transient Hetzner API failure
     * leaves the gauge at its last-good value rather than gapping, and
     * persistent staleness is observable as a flat line on the dashboard.
     */
    public void refreshMetrics() {
        try {
            runningNodeCount();
        } catch (Exception e) {
            log.warn("Refresh of hetzner_running_servers failed for cloud '{}': {}",
                    name, e.getMessage());
        }
        // PROVISIONING_PENDING tracks an in-memory AtomicInteger, so it
        // does not drift from the network; re-emit defensively in case a
        // provisioning code path forgot to update the gauge after mutating
        // the counter.
        HetznerMetricProvider.PROVISIONING_PENDING.labels(name).set(ensurePendingProvisions().get());
    }

    /**
     * Decrement the pending provision counter. Called by NodeCallable when
     * provisioning completes (success or failure).
     */
    void provisionCompleted() {
        int prev = ensurePendingProvisions().getAndDecrement();
        if (prev <= 0) {
            ensurePendingProvisions().set(0);
            log.warn("pendingProvisions underflow corrected (was {})", prev);
            HetznerMetricProvider.PROVISION_UNDERFLOW.labels(name).inc();
        }
        HetznerMetricProvider.PROVISIONING_PENDING.labels(name).set(ensurePendingProvisions().get());
    }

    @Override
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",
            justification = "Defensive catch prevents uncaught exceptions from killing CRW timer")
    public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
        log.debug("provision(cloud={},label={},excessWorkload={})", name, state.getLabel(), excessWorkload);
        final List<PlannedNode> plannedNodes = new ArrayList<>();
        final Label label = state.getLabel();
        final List<HetznerServerTemplate> matchingTemplates = getTemplates(label);
        final Jenkins jenkinsInstance = Jenkins.get();
        try {
            while (excessWorkload > 0) {
                if (jenkinsInstance.isQuietingDown() || jenkinsInstance.isTerminating()) {
                    log.warn("Jenkins is going down, no new nodes will be provisioned");
                    HetznerMetricProvider.PROVISION_SKIPPED.labels(name, "jenkins_quieting").inc();
                    break;
                }
                HetznerApiClient apiClient = HetznerApiClient.forCredentials(credentialsId);
                if (apiClient.isRateLimited()) {
                    log.warn("Hetzner API token rate-limited, suppressing provisioning for cloud '{}' "
                            + "(remaining={}, resets in {}s)",
                            name, apiClient.getRemaining(), apiClient.timeUntilReset().toSeconds());
                    HetznerMetricProvider.PROVISION_SKIPPED.labels(name, "rate_limited").inc();
                    break;
                }
                int running = effectiveNodeCount();
                int instanceCap = getInstanceCap();
                int available = instanceCap - running;
                // INSTANCE_CAP is published from publishConfigMetrics() at
                // readResolve() -- not on the hot path. Cap doesn't change
                // between provision() calls, so a per-call set() is wasted work.
                final List<HetznerServerTemplate> rankedTemplates = rankTemplatesByHealth(matchingTemplates);
                final HetznerServerTemplate template = rankedTemplates.get(0);
                if (TemplateErrorTracker.isSuppressed(template.getName())) {
                    log.warn("Template '{}' suppressed due to recurring config errors "
                            + "(image={}). Provisioning skipped; fix template config "
                            + "or check Hetzner changelog.", template.getName(), template.getImage());
                    HetznerMetricProvider.PROVISION_SKIPPED.labels(name, "template_suppressed").inc();
                    break;
                }
                log.info("Creating new agent with {} executors, have {} running VMs "
                        + "(pending={})", template.getNumExecutors(), running,
                        ensurePendingProvisions().get());
                if (available <= 0) {
                    log.warn("Cloud capacity reached ({}). Has {} VMs running+pending, "
                            + "but want {} more executors",
                            instanceCap, running, excessWorkload);
                    HetznerMetricProvider.PROVISION_SKIPPED.labels(name, "cap_reached").inc();
                    break;
                } else {
                    ensurePendingProvisions().incrementAndGet();
                    HetznerMetricProvider.PROVISIONING_PENDING.labels(name).set(ensurePendingProvisions().get());
                    // Anything between incrementAndGet() and a successful submit can
                    // leak the increment (template.createAgent throws, the executor
                    // rejects with RejectedExecutionException at shutdown, etc.).
                    // Decrement on any non-submission failure path so cap accounting
                    // does not drift across the lifetime of the JVM.
                    boolean submitted = false;
                    try {
                        final String serverName = template.generateNodeName();
                        final ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(name, template.getName(),
                                serverName);
                        final HetznerServerAgent agent = template.createAgent(provisioningId, serverName);
                        agent.setMode(template.getMode());
                        plannedNodes.add(new TrackedPlannedNode(
                                        provisioningId,
                                        agent.getNumExecutors(),
                                        Computer.threadPoolForRemoting.submit(
                                                new NodeCallable(agent, this, rankedTemplates))
                                )
                        );
                        submitted = true;
                        excessWorkload -= agent.getNumExecutors();
                    } finally {
                        if (!submitted) {
                            // NodeCallable.call() will never run, so its finally
                            // block can't restore ensurePendingProvisions(). Do it here.
                            provisionCompleted();
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Unable to provision node for cloud '{}', label '{}'", name, label, e);
            HetznerMetricProvider.PROVISION_UNCAUGHT.labels(name).inc();
        }
        return plannedNodes;
    }

    @Override
    public boolean canProvision(CloudState state) {
        return !getTemplates(state.getLabel()).isEmpty();
    }

    private List<HetznerServerTemplate> getTemplates(Label label) {
        return serverTemplates.stream().filter(t -> {
                    //no labels has been provided in template
                    if (t.getLabels().isEmpty()) {
                        return Node.Mode.NORMAL.equals(t.getMode());
                    } else {
                        if (Node.Mode.NORMAL.equals(t.getMode())) {
                            return label == null || label.matches(t.getLabels());
                        } else {
                            return label != null && label.matches(t.getLabels());
                        }
                    }
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        @NonNull
        public String getDisplayName() {
            return Messages.plugin_displayName();
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doVerifyConfiguration(@QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            final ConfigurationValidator.ValidationResult result = ConfigurationValidator.validateCloudConfig(credentialsId);
            if (result.isSuccess()) {
                return FormValidation.ok(Messages.cloudConfigPassed());
            } else {
                return FormValidation.error(result.getMessage());
            }
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public FormValidation doCheckCloudName(@QueryParameter String name) {
            if (Helper.isValidLabelValue(name)) {
                return FormValidation.ok();
            }
            return FormValidation.error("Cloud name is not a valid label value: %s", name);
        }

        @Restricted(NoExternalUse.class)
        @RequirePOST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item owner) {
            final StandardListBoxModel result = new StandardListBoxModel();
            if (owner == null) {
                if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                    return result;
                }
            } else {
                if (!owner.hasPermission(Item.EXTENDED_READ)
                        && !owner.hasPermission(CredentialsProvider.USE_ITEM)) {
                    return result;
                }
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, owner, StringCredentialsImpl.class,
                            Collections.emptyList(), CredentialsMatchers.always());
        }
    }
}
