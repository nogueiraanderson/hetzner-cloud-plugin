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

import cloud.dnation.hetznerclient.ServerType;
import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
class NodeCallable implements Callable<Node> {
    private final HetznerServerAgent agent;
    private final HetznerCloud cloud;
    private final List<HetznerServerTemplate> rankedTemplates;

    /**
     * Constructor with DC failover support.
     * @param agent the agent being provisioned (uses first template)
     * @param cloud the cloud instance
     * @param rankedTemplates templates sorted by DC health (healthy first)
     */
    NodeCallable(HetznerServerAgent agent, HetznerCloud cloud,
                 List<HetznerServerTemplate> rankedTemplates) {
        this.agent = agent;
        this.cloud = cloud;
        this.rankedTemplates = rankedTemplates;
    }

    /** Backward-compatible constructor (no failover). */
    NodeCallable(HetznerServerAgent agent, HetznerCloud cloud) {
        this(agent, cloud, Collections.singletonList(agent.getTemplate()));
    }

    @Override
    public Node call() throws Exception {
        try {
            Computer computer = agent.getComputer();
            if (computer != null && computer.isOnline()) {
                return agent;
            }

            // Try provisioning with failover across DCs
            Exception lastException = null;
            for (int i = 0; i < rankedTemplates.size(); i++) {
                HetznerServerTemplate template = rankedTemplates.get(i);
                String location = template.getLocation();
                try {
                    Node result = doProvisionAndTime(template);
                    DcHealthTracker.recordSuccess(location);
                    TemplateErrorTracker.recordSuccess(template.getName());
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(), "success").inc();
                    return result;
                } catch (HetznerProvisioningException e) {
                    lastException = e;
                    if (e.isRateLimited()) {
                        // Rate limit is token-scoped, not DC-scoped. All DCs share
                        // the same token; retrying another DC just wastes quota.
                        // isRateLimited() now requires BOTH HTTP 429 AND code
                        // "rate_limit_exceeded" so instance_cap_reached (also 429)
                        // no longer false-aborts failover.
                        HetznerApiClient client = HetznerApiClient.forCredentials(cloud.getCredentialsId());
                        log.warn("Token rate-limited during provisioning of '{}' in DC {} "
                                + "(remaining={}, resets in {}s), aborting failover",
                                agent.getNodeName(), location,
                                client.getRemaining(), client.timeUntilReset().toSeconds());
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(), "rate_limited").inc();
                        throw e;
                    }
                    if (e.isConfigError()) {
                        // Config errors are template-scoped, not DC-scoped.
                        // Trying another DC with the same bad image/config won't help.
                        TemplateErrorTracker.recordError(template.getName(), e.getMessage());
                        log.error("Template '{}' config error in DC {}: {} "
                                + "(code={}, image={}). DC failover skipped; "
                                + "check Hetzner changelog for image deprecation.",
                                template.getName(), location, e.getMessage(),
                                e.getHetznerErrorCode(), template.getImage());
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(), "config_error").inc();
                        throw e;
                    }
                    if ("instance_cap_reached".equals(e.getHetznerErrorCode())) {
                        // Cloud-level capacity bookkeeping (HTTP 429 with our own
                        // synthetic "instance_cap_reached" code from createServer's
                        // under-lock cap recheck). Not a DC health signal: another
                        // DC will hit the same cloud-wide cap. Skip DC failure
                        // recording so capacity bursts don't poison healthy DCs.
                        log.warn("Cloud cap reached during burst provisioning (location={}); "
                                + "skipping DC health record and failover", location);
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(), "cap_reached_under_lock").inc();
                        throw e;
                    }
                    DcHealthTracker.recordFailure(location);
                    // Failover decision uses isPlausiblyDcAttributable() not the strict
                    // isResourceUnavailable() check. Hetzner introduces new error codes
                    // regularly; an unknown 422 or any 5xx is plausibly a DC issue and
                    // worth one retry on another DC before surfacing the failure.
                    //
                    // Additionally gate on isFailoverCompatibleWith(): the agent was
                    // built from the FIRST ranked template, so swapping to a template
                    // with different labels / connector / executors / remoteFs would
                    // create a server whose Jenkins-side metadata is wrong (wrong SSH
                    // credentials, wrong workspace, wrong queue matcher). When the next
                    // template is incompatible we treat this as a final failure rather
                    // than chasing more wrong-metadata bootstraps.
                    if (e.isPlausiblyDcAttributable() && i < rankedTemplates.size() - 1) {
                        HetznerServerTemplate next = rankedTemplates.get(i + 1);
                        HetznerServerTemplate baseline = agent.getTemplate();
                        if (baseline != null && !baseline.isFailoverCompatibleWith(next)) {
                            log.warn("DC failover from {} to {} aborted: template '{}' is not "
                                    + "failover-compatible with '{}' (differs on labels/executors/"
                                    + "remoteFs/mode/connector). Treating as final failure.",
                                    location, next.getLocation(), baseline.getName(), next.getName());
                            HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                    cloud.name, template.getName(), "failover_incompatible").inc();
                            throw e;
                        }
                        String outcomeLabel = e.isResourceUnavailable() ? "dc_unavailable" : "dc_attributable";
                        log.warn("DC {} attributable failure ({}/{}), trying next DC ({}/{})",
                                location, e.getHttpStatus(), e.getHetznerErrorCode(),
                                i + 1, rankedTemplates.size());
                        HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                                cloud.name, template.getName(), outcomeLabel).inc();
                        HetznerMetricProvider.DC_FAILOVER.labels(
                                location != null ? location : "",
                                next.getLocation() != null ? next.getLocation() : "").inc();
                        continue;
                    }
                    // Non-retryable error or last template; give up
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(), "failure").inc();
                    throw e;
                } catch (java.io.IOException ioe) {
                    // Launcher / SSH / remoting failures during the post-create
                    // bootstrap phase. These can be one-off (slow sshd boot) OR
                    // DC-level (network blip, DC-scoped firewall/ssh outage). We
                    // record a soft DC failure so chronic DC-level launcher rot
                    // can trip the breaker over time, while a single slow boot
                    // doesn't (threshold is 2 consecutive).
                    lastException = ioe;
                    DcHealthTracker.recordFailure(location);
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(), "bootstrap_error").inc();
                    throw ioe;
                } catch (Exception other) {
                    // Non-IO bootstrap failures: arch mismatch, addNode failure,
                    // unexpected runtime. NOT recorded as DC failure - these are
                    // typically Jenkins-side or config issues, not DC health.
                    lastException = other;
                    HetznerMetricProvider.PROVISION_ATTEMPTS.labels(
                            cloud.name, template.getName(), "bootstrap_error").inc();
                    throw other;
                }
            }
            // Should not reach here, but just in case
            throw lastException != null ? lastException
                    : new IllegalStateException("No templates available for provisioning");
        } finally {
            // Decrement pending counter so subsequent cap checks are accurate.
            // Must run on both success and failure paths.
            cloud.provisionCompleted();
        }
    }

    /**
     * Wrap {@link #doProvision(HetznerServerTemplate)} with a Histogram timer
     * so per-attempt wall time is observable in Prometheus.
     * Outcome label is {@code success} or {@code failure}.
     */
    private Node doProvisionAndTime(HetznerServerTemplate template) throws Exception {
        final long startNanos = System.nanoTime();
        String outcome = "failure";
        try {
            Node result = doProvision(template);
            outcome = "success";
            return result;
        } finally {
            double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            String dc = template.getLocation() != null ? template.getLocation() : "";
            HetznerMetricProvider.PROVISION_DURATION
                    .labels(cloud.name, template.getName(), dc, outcome).observe(seconds);
        }
    }

    /**
     * Provision a single server using the given template.
     * Extracted from the original call() method for retry support.
     */
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",
            justification = "Broad catch ensures leaked servers are destroyed on any failure type")
    private Node doProvision(HetznerServerTemplate template) throws Exception {
        // Pass the iteration template explicitly to createServer so DC failover
        // actually targets a different DC/image/server-type. agent.getTemplate()
        // is final and set to the FIRST ranked template; without this overload
        // the failover loop is cosmetic - every iteration would create a server
        // in the original DC regardless of which template the loop is on.
        final HetznerServerInfo serverInfo = cloud.getResourceManager().createServer(agent, template);
        final String serverName = serverInfo.getServerDetail().getName();
        // Track Jenkins-side state for cleanup: a ghost node would block queue
        // routing if we destroyed the Hetzner server but left the Node entry.
        boolean nodeAddedToJenkins = false;
        try {
            agent.setServerInstance(serverInfo);
            boolean running = false;
            final int bootDeadline = template.getBootDeadline();
            //wait for status == "running", but at most bootDeadline minutes
            final WaitStrategy waitStrategy = new WaitStrategy(bootDeadline, 45, 15);
            while (!waitStrategy.isDeadLineOver()) {
                waitStrategy.waitNext();
                HetznerApiClient bootClient = HetznerApiClient.forCredentials(cloud.getCredentialsId());
                if (bootClient.isRateLimited()) {
                    log.debug("Rate-limited, skipping boot status poll for '{}' (resets in {}s)",
                            serverName, bootClient.timeUntilReset().toSeconds());
                    continue;
                }
                if (agent.isAlive()) {
                    log.info("Server '{}' is now running, waiting 3 seconds before proceeding", serverName);
                    Uninterruptibles.sleepUninterruptibly(3, TimeUnit.SECONDS);
                    running = true;
                    break;
                }
            }
            Preconditions.checkState(running,
                    "Server '%s' (id=%s) didn't reach 'running' state within %s minute(s), giving up",
                    serverName, serverInfo.getServerDetail().getId(), bootDeadline);

            // Option A: Pre-boot architecture validation via API response.
            validateArchitectureFromApi(template.getServerType(),
                    serverInfo.getServerDetail().getServerType(), serverName);

            Jenkins.get().addNode(agent);
            nodeAddedToJenkins = true;
            Computer computer = agent.toComputer();
            int maxRetries = 5;
            int attempt = 0;
            boolean connected = false;
            if (computer != null) {
                while (attempt < maxRetries) {
                    attempt++;
                    // Respect the per-attempt boot deadline so we don't keep retrying
                    // SSH connect indefinitely after the launcher has already burnt
                    // through the launcher-side retry budget. Without this, a wrong-arch
                    // or sshd-never-up VM costs an extra ~50s before destroy.
                    long remainingMs = waitStrategy.remainingMillis();
                    if (remainingMs <= 0) {
                        log.warn("Connection to '{}' aborted: boot deadline exceeded "
                                + "(attempted {}/{})", computer.getDisplayName(), attempt, maxRetries);
                        break;
                    }
                    // Cap each attempt to min(remaining, 60s) so a hung Future doesn't
                    // block forever beyond the deadline. computer.connect(...).get()
                    // was previously unbounded; a slow launcher could pin the
                    // provisioning thread past bootDeadline.
                    long attemptMs = Math.min(remainingMs, 60_000L);
                    java.util.concurrent.Future<?> connectFuture = null;
                    try {
                        connectFuture = computer.connect(false);
                        connectFuture.get(attemptMs, TimeUnit.MILLISECONDS);
                        connected = true;
                        break;
                    } catch (java.util.concurrent.TimeoutException te) {
                        log.warn("Connection to '{}' timed out after {}ms (attempt {}/{})",
                                computer.getDisplayName(), attemptMs, attempt, maxRetries);
                        if (connectFuture != null) {
                            connectFuture.cancel(true);
                        }
                        // No sleep - the timeout already consumed wall-clock time
                    } catch (InterruptedException | ExecutionException e) {
                        log.warn("Connection to '{}' has failed (attempt {}/{})",
                                computer.getDisplayName(), attempt, maxRetries, e);
                        if (waitStrategy.remainingMillis() > 0) {
                            TimeUnit.SECONDS.sleep(10);
                        }
                    }
                }
                if (!connected) {
                    throw new IllegalStateException(String.format(
                            "Failed to connect to '%s' after %d attempt(s) (boot deadline %s)",
                            computer.getName(), attempt,
                            waitStrategy.isDeadLineOver() ? "expired" : "remaining"));
                }
            } else {
                throw new IllegalStateException(
                        "No computer object in agent '" + agent.getDisplayName()
                        + "' (server id=" + serverInfo.getServerDetail().getId() + ")");
            }
            // Option C: Post-boot architecture validation via uname -m.
            validateArchitectureFromHardware(computer, template.getServerType(), serverName);

            return agent;
        } catch (Exception e) {
            log.error("Failed to bootstrap server '{}', attempting cleanup", serverName, e);
            // destroyServer returns boolean: true on confirmed delete, false if
            // the underlying API call swallowed an exception. Branch metrics on
            // the actual outcome so PROVISION_LEAKED_SERVERS no longer over-
            // reports successful cleanups when Hetzner rejected the delete.
            boolean destroyed = cloud.getResourceManager().destroyServer(serverInfo.getServerDetail());
            if (destroyed) {
                log.warn("Destroyed leaked server '{}'", serverName);
                HetznerMetricProvider.PROVISION_LEAKED_SERVERS
                        .labels(cloud.name, template.getName()).inc();
            } else {
                log.error("Failed to destroy leaked server '{}' (id={}), manual cleanup required "
                        + "(OrphanedNodesCleaner will retry)",
                        serverName, serverInfo.getServerDetail().getId());
                HetznerMetricProvider.PROVISION_LEAK_DESTROY_FAILURES
                        .labels(cloud.name, template.getName()).inc();
            }
            // Ghost-node prevention: addNode() may have succeeded before connect/
            // hardware-validation failed. If so, remove the Jenkins Node so it
            // doesn't sit around offline blocking queue routing for its labels.
            // Also belt-and-suspenders: lookup by node name even when our flag
            // says it wasn't added, because addNode() can mutate Jenkins state
            // and then throw partway through save (Opus review M4).
            String nodeName = agent.getNodeName();
            boolean shouldTryRemove = nodeAddedToJenkins
                    || (nodeName != null && Jenkins.get().getNode(nodeName) != null);
            if (shouldTryRemove) {
                try {
                    // Re-resolve via Jenkins.getNode in case our `agent` reference
                    // has been swapped or invalidated; tolerates already-removed.
                    hudson.model.Node existing = Jenkins.get().getNode(nodeName);
                    if (existing != null) {
                        Jenkins.get().removeNode(existing);
                        log.warn("Removed Jenkins node '{}' after bootstrap failure", nodeName);
                    }
                } catch (Exception removeEx) {
                    log.error("Failed to remove ghost Jenkins node '{}' after bootstrap failure; "
                            + "manual cleanup may be required", nodeName, removeEx);
                }
            }
            throw e;
        }
    }

    /**
     * Infer expected CPU architecture from Hetzner server type name.
     * Known ARM prefixes: cax (Ampere Altra). Known x86_64 prefixes: cx, cpx, ccx.
     * Anything else logs a WARNING and defaults to x86_64 so the post-boot uname
     * check (Option C) can still catch real mismatches without forcing a hard
     * failure here. Hetzner introduces new server-type families regularly; the
     * warning surfaces unknown prefixes in logs for an operator to add to the
     * known-set explicitly.
     *
     * @param serverType Hetzner server type name (e.g., "cax41", "cpx62")
     * @return "arm64" or "x86_64"
     */
    private static final java.util.Set<String> KNOWN_ARM_PREFIXES = java.util.Set.of("cax");
    private static final java.util.Set<String> KNOWN_X86_PREFIXES = java.util.Set.of("cx", "cpx", "ccx");
    /** Dedup the "unknown family" warning to log at most once per family-per-JVM. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> WARNED_FAMILIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static String inferArchFromServerType(String serverType) {
        if (serverType == null || serverType.isEmpty()) {
            return "x86_64";
        }
        // Extract the alphabetic family name up to the first digit. Earlier
        // versions used startsWith() which incorrectly classified "cxx11" as
        // "cx" (x86_64) - any future ARM family with a name like "cxg11"
        // would silently default to x86_64 instead of warning.
        String family = extractFamilyPrefix(serverType.toLowerCase(Locale.ROOT));
        if (family.isEmpty()) {
            log.warn("Server type '{}' has no alphabetic prefix; defaulting to x86_64", serverType);
            return "x86_64";
        }
        if (KNOWN_ARM_PREFIXES.contains(family)) {
            return "arm64";
        }
        if (KNOWN_X86_PREFIXES.contains(family)) {
            return "x86_64";
        }
        if (WARNED_FAMILIES.putIfAbsent(family, Boolean.TRUE) == null) {
            log.warn("Unknown Hetzner server type family '{}' (from '{}') - defaulting to x86_64. "
                    + "If this is an ARM family, update KNOWN_ARM_PREFIXES; the post-boot "
                    + "uname check is the safety net for now.", family, serverType);
        }
        return "x86_64";
    }

    /** Extracts the alphabetic prefix up to (but not including) the first digit. */
    private static String extractFamilyPrefix(String s) {
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) {
            i++;
        }
        return s.substring(0, i);
    }

    /**
     * Option A: Validate architecture from the Hetzner API response.
     * After server creation, the API returns the actual server_type which may
     * differ from what was requested during availability incidents.
     */
    private static void validateArchitectureFromApi(String requestedType,
                                                    ServerType actualType,
                                                    String serverName) {
        if (actualType == null || actualType.getName() == null) {
            log.warn("Cannot validate architecture for '{}': server_type not in API response", serverName);
            return;
        }
        String expectedArch = inferArchFromServerType(requestedType);
        String actualArch = inferArchFromServerType(actualType.getName());
        if (!expectedArch.equals(actualArch)) {
            // Use the parsed arch ({arm64, x86_64}) as the "actual" label
            // value. The raw server type name (e.g., cax41/cpx62) is bounded
            // but the parsed arch is more useful for alerting and matches
            // the hardware-phase label.
            HetznerMetricProvider.ARCH_VALIDATION_FAILURES
                    .labels("api", requestedType != null ? requestedType : "", actualArch).inc();
            throw new IllegalStateException(String.format(
                    "Architecture mismatch for server '%s': requested type '%s' (%s) "
                    + "but Hetzner provisioned type '%s' (%s). "
                    + "This may indicate a Hetzner availability incident.",
                    serverName, requestedType, expectedArch,
                    actualType.getName(), actualArch));
        }
        log.debug("Architecture validated for '{}': requested='{}' actual='{}' arch={}",
                serverName, requestedType, actualType.getName(), actualArch);
    }

    /**
     * Option C: Validate architecture from actual hardware via uname -m.
     * Runs after SSH connection is established. This is the ground-truth check
     * that catches mismatches the API might not report.
     */
    private static void validateArchitectureFromHardware(Computer computer,
                                                         String requestedType,
                                                         String serverName) {
        String expectedArch = inferArchFromServerType(requestedType);
        try {
            VirtualChannel channel = computer.getChannel();
            if (channel == null) {
                log.warn("Cannot validate hardware architecture for '{}': no remoting channel", serverName);
                return;
            }
            // uname -m returns: x86_64, aarch64, armv7l, etc.
            // Use exact normalized match instead of substring "arm" - the substring
            // form matches armv7l (32-bit ARM) and would also false-match any kernel
            // build string or path that happens to contain "arm".
            String uname = channel.call(new UnameCallable()).trim();
            String unameLower = uname.toLowerCase(Locale.ROOT);
            String hardwareArch;
            if ("aarch64".equals(unameLower) || "arm64".equals(unameLower)) {
                hardwareArch = "arm64";
            } else if ("x86_64".equals(unameLower) || "amd64".equals(unameLower)) {
                hardwareArch = "x86_64";
            } else {
                log.warn("Unrecognized 'uname -m' output '{}' on server '{}'; treating as x86_64 "
                        + "for compatibility. Verify the hardware architecture manually.", uname, serverName);
                hardwareArch = "x86_64";
            }
            if (!expectedArch.equals(hardwareArch)) {
                // Use the parsed arch ({arm64, x86_64}) as the "actual" label
                // value, NOT the raw `uname` output (which can include kernel
                // build strings / hostnames -> unbounded cardinality).
                HetznerMetricProvider.ARCH_VALIDATION_FAILURES
                        .labels("hardware", requestedType != null ? requestedType : "",
                                hardwareArch).inc();
                throw new IllegalStateException(String.format(
                        "Hardware architecture mismatch for server '%s': "
                        + "requested type '%s' (expected %s) but hardware reports '%s' (%s). "
                        + "Hetzner provisioned wrong architecture.",
                        serverName, requestedType, expectedArch, uname, hardwareArch));
            }
            log.info("Hardware architecture validated for '{}': uname={} expected={}", serverName, uname, expectedArch);
        } catch (IllegalStateException e) {
            throw e; // re-throw arch mismatch
        } catch (Exception e) {
            log.warn("Could not validate hardware architecture for '{}': {}", serverName, e.getMessage());
            // Don't fail the build for validation errors, only for confirmed mismatches
        }
    }

    /**
     * Remoting callable that executes uname -m on the agent.
     */
    private static final class UnameCallable extends jenkins.security.MasterToSlaveCallable<String, Exception> {
        private static final long serialVersionUID = 1L;

        @Override
        public String call() throws Exception {
            try {
                ProcessBuilder pb = new ProcessBuilder("uname", "-m");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                int exit = proc.waitFor();
                if (exit == 0 && !output.isEmpty()) {
                    return output;
                }
            } catch (IOException | InterruptedException e) {
                // Fall back to JVM property if uname is unavailable (e.g., Windows agent)
            }
            return System.getProperty("os.arch", "unknown");
        }
    }

    private static final class WaitStrategy {
        private final int firstInterval;
        private final int subsequentIntervals;
        private final long deadlineNanos;
        private boolean first = true;

        private WaitStrategy(int deadlineMinutes, int firstInterval, int subsequentIntervals) {
            deadlineNanos = System.nanoTime() + deadlineMinutes * 60L * 1_000_000_000L;
            this.firstInterval = firstInterval;
            this.subsequentIntervals = subsequentIntervals;
        }

        boolean isDeadLineOver() {
            return System.nanoTime() > deadlineNanos;
        }

        /** Milliseconds left before the deadline, or 0 if already past. */
        long remainingMillis() {
            long remainingNanos = deadlineNanos - System.nanoTime();
            return remainingNanos <= 0 ? 0L : remainingNanos / 1_000_000L;
        }

        void waitNext() {
            final int waitSeconds;
            if (first) {
                first = false;
                waitSeconds = firstInterval;
            } else {
                waitSeconds = subsequentIntervals;
            }
            Uninterruptibles.sleepUninterruptibly(waitSeconds, TimeUnit.SECONDS);
        }
    }
}
