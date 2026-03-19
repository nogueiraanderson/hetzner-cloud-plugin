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
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Uninterruptibles;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
class NodeCallable implements Callable<Node> {
    private final HetznerServerAgent agent;
    private final HetznerCloud cloud;

    @Override
    public Node call() throws Exception {
        Computer computer = agent.getComputer();
        if (computer != null && computer.isOnline()) {
            return agent;
        }
        final HetznerServerInfo serverInfo = cloud.getResourceManager().createServer(agent);
        agent.setServerInstance(serverInfo);
        final String serverName = serverInfo.getServerDetail().getName();
        try {
            boolean running = false;
            final int bootDeadline = agent.getTemplate().getBootDeadline();
            //wait for status == "running", but at most bootDeadline minutes
            final WaitStrategy waitStrategy = new WaitStrategy(bootDeadline, 45, 15);
            while (!waitStrategy.isDeadLineOver()) {
                waitStrategy.waitNext();
                if (agent.isAlive()) {
                    log.info("Server '{}' is now running, waiting 10 seconds before proceeding", serverName);
                    Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS);
                    running = true;
                    break;
                }
            }
            Preconditions.checkState(running,
                    "Server '%s' (id=%s) didn't reach 'running' state within %s minute(s), giving up",
                    serverName, serverInfo.getServerDetail().getId(), bootDeadline);

            // Option A: Pre-boot architecture validation via API response.
            // The Hetzner API returns the actual server_type in the response,
            // which may differ from what was requested during availability incidents.
            validateArchitectureFromApi(agent.getTemplate().getServerType(),
                    serverInfo.getServerDetail().getServerType(), serverName);

            Jenkins.get().addNode(agent);
            computer = agent.toComputer();
            int retry = 5;
            boolean connected = false;
            if (computer != null) {
                while (--retry > 0) {
                    try {
                        computer.connect(false).get();
                        connected = true;
                        break;
                    } catch (InterruptedException | ExecutionException e) {
                        log.warn("Connection to '{}' has failed, remaining retries {}",
                                computer.getDisplayName(), retry, e);
                        TimeUnit.SECONDS.sleep(10);
                    }
                }
                if (!connected) {
                    throw new IllegalStateException(
                            "Failed to connect to '" + computer.getName() + "' after 5 retries");
                }
            } else {
                throw new IllegalStateException(
                        "No computer object in agent '" + agent.getDisplayName()
                        + "' (server id=" + serverInfo.getServerDetail().getId() + ")");
            }
            // Option C: Post-boot architecture validation via uname -m.
            // Ground truth from the actual hardware, catches mismatches that
            // even the API might not report (e.g., Hetzner availability incidents
            // where ARM requests get fulfilled with x86 hardware).
            validateArchitectureFromHardware(computer, agent.getTemplate().getServerType(), serverName);

            return agent;
        } catch (Exception e) {
            log.error("Failed to bootstrap server '{}', attempting cleanup", serverName, e);
            try {
                cloud.getResourceManager().destroyServer(serverInfo.getServerDetail());
                log.info("Destroyed leaked server '{}'", serverName);
            } catch (Exception cleanupEx) {
                log.error("Failed to destroy leaked server '{}' (id={}), manual cleanup required",
                        serverName, serverInfo.getServerDetail().getId(), cleanupEx);
            }
            throw e;
        }
    }

    /**
     * Infer expected CPU architecture from Hetzner server type name.
     * CAX series (cax11, cax21, cax31, cax41) = ARM64 (Ampere Altra).
     * All others (cx, cpx, ccx, cx, etc.) = x86_64 (Intel/AMD).
     *
     * @param serverType Hetzner server type name (e.g., "cax41", "cpx62")
     * @return "arm64" or "x86_64"
     */
    static String inferArchFromServerType(String serverType) {
        if (serverType != null && serverType.toLowerCase(Locale.ROOT).startsWith("cax")) {
            return "arm64";
        }
        return "x86_64";
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
            String uname = channel.call(new UnameCallable()).trim();
            String hardwareArch = uname.contains("aarch64") || uname.contains("arm") ? "arm64" : "x86_64";
            if (!expectedArch.equals(hardwareArch)) {
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
