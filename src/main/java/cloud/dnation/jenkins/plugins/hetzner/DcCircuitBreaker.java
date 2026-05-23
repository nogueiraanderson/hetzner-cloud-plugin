/*
 * DC-level circuit breaker for Hetzner Cloud provisioning.
 * Tracks consecutive failures per datacenter location and short-circuits
 * provisioning attempts to broken DCs, forcing failover to healthy ones.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.jenkins.plugins.hetzner.metrics.HetznerMetricProvider;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@XStreamAlias("dcCircuitBreaker")
class DcCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = 2;
    private static final long RESET_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    // v103.percona.26: forced-reset TTL for HALF_OPEN. If the probe-holder
    // dies between consuming the lease and calling recordSuccess/recordFailure
    // (thread crash, OOM, non-DC-attributable bootstrap_other exception
    // path), the breaker would otherwise be pinned in HALF_OPEN forever
    // with no lease. After this TTL, isProbeable() re-arms the lease so a
    // fresh probe can re-evaluate the DC.
    private static final long HALF_OPEN_STALE_TTL_MS = 2 * RESET_TIMEOUT_MS; // 10 minutes

    // Not final: XStream deserialization assigns fields directly, and
    // afterLoad() may fill in a fallback location/arch from the persisted
    // map key if older XML omitted them.
    @Getter
    private String location;
    // v103.percona.25: per-arch breaker key. One of {amd64, arm64, unknown}
    // via HetznerMetricProvider.archOf(). Legacy XML (pre-v25) lacks this
    // field; afterLoad() fills it from the decoded map key on first load.
    @Getter
    private String arch;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0;
    private long lastSuccessAt = System.currentTimeMillis();
    private long lastFailureAt = 0;

    // v103.percona.26: HALF_OPEN single-probe lease. true = lease is available
    // (next tryAcquireProbe() caller in HALF_OPEN state gets the probe).
    // Re-armed whenever the breaker enters HALF_OPEN (OPEN reset-timeout
    // elapse) and when HALF_OPEN_STALE_TTL_MS elapses without a recorded
    // outcome. Consumed by tryAcquireProbe() only; isProbeable() is a
    // non-consuming peek used by filterHealthy/sortByHealth so a *filter*
    // operation does not steal the probe from the actual provisioner.
    // Transient: in-memory only; meaningless across JVM restart.
    private transient boolean halfOpenProbeAvailable;
    private transient long halfOpenEnteredAt;

    DcCircuitBreaker(String location, String arch) {
        this.location = location;
        this.arch = arch;
        // Initialize gauge with starting state so panels render immediately
        // instead of "no data" until the first transition.
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(State.CLOSED.ordinal());
    }

    /**
     * Record a state transition to Prometheus. Called from the four sites
     * where {@code state} is mutated (isHealthy reset, recordSuccess close,
     * recordFailure open, getState lazy reset).
     */
    private void recordTransition(State from, State to) {
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(to.ordinal());
        HetznerMetricProvider.DC_BREAKER_TRANSITIONS.labels(location, arch, from.name(), to.name()).inc();
    }

    /**
     * Non-consuming peek: would this DC accept a probe right now?
     * Used by {@link DcHealthTracker#filterHealthy} and
     * {@link DcHealthTracker#sortByHealth} which need a read-only health
     * view; consuming the probe-lease inside a filter would prevent the
     * actual provisioner from ever attempting the probe (Codex post-impl
     * finding, v103.percona.26).
     *
     * <p>CLOSED: always yes. OPEN: yes only if reset timeout has elapsed
     * (and we lazily transition to HALF_OPEN, arming the lease).
     * HALF_OPEN: yes if the lease is available OR if the HALF_OPEN window
     * is older than {@link #HALF_OPEN_STALE_TTL_MS} (forced re-arm to
     * handle probe-holder-died-without-recording).
     */
    synchronized boolean isProbeable() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
                enterHalfOpen("reset timeout elapsed");
                // Fall through to HALF_OPEN branch.
            } else {
                return false;
            }
        }
        // HALF_OPEN.
        rearmStaleHalfOpenLeaseIfNeeded();
        return halfOpenProbeAvailable;
    }

    /**
     * Consuming probe lease acquisition. Returns true exactly once per
     * HALF_OPEN window (the lease-holder); subsequent callers see false
     * until {@link #recordSuccess()} closes the breaker (lease becomes
     * irrelevant) or {@link #recordFailure()} reopens it. CLOSED state
     * always returns true (no lease semantics in CLOSED).
     *
     * <p>Called by {@link cloud.dnation.jenkins.plugins.hetzner.NodeCallable}
     * just before {@code createServer()} so the lease is consumed by the
     * actual provisioner, not by an earlier filter or sort pass.
     */
    synchronized boolean tryAcquireProbe() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
                enterHalfOpen("reset timeout elapsed");
            } else {
                return false;
            }
        }
        // HALF_OPEN.
        rearmStaleHalfOpenLeaseIfNeeded();
        if (halfOpenProbeAvailable) {
            halfOpenProbeAvailable = false;
            return true;
        }
        return false;
    }

    /** Transition OPEN -> HALF_OPEN and arm a fresh probe lease. */
    private void enterHalfOpen(String reason) {
        State previous = state;
        state = State.HALF_OPEN;
        halfOpenProbeAvailable = true;
        halfOpenEnteredAt = System.currentTimeMillis();
        log.info("DC {} arch {} circuit breaker: {} -> HALF_OPEN ({})",
                location, arch, previous, reason);
        recordTransition(previous, State.HALF_OPEN);
    }

    /**
     * Re-arm the HALF_OPEN probe lease if the window has been open longer
     * than {@link #HALF_OPEN_STALE_TTL_MS} without a recorded outcome.
     * Handles the probe-holder-died-without-recording case (SA-R2 finding):
     * a NodeCallable that consumed the lease but then crashed or hit a
     * non-DC-attributable exit path without calling recordSuccess /
     * recordFailure would otherwise pin the breaker out of rotation
     * forever.
     */
    private void rearmStaleHalfOpenLeaseIfNeeded() {
        if (!halfOpenProbeAvailable
                && halfOpenEnteredAt > 0
                && System.currentTimeMillis() - halfOpenEnteredAt >= HALF_OPEN_STALE_TTL_MS) {
            halfOpenProbeAvailable = true;
            halfOpenEnteredAt = System.currentTimeMillis();
            HetznerMetricProvider.DC_HEALTH_STALE_HALF_OPEN_RESETS.labels(location, arch).inc();
            log.warn("DC {} arch {} circuit breaker: stale HALF_OPEN lease re-armed "
                    + "(probe-holder did not record outcome within {}ms)",
                    location, arch, HALF_OPEN_STALE_TTL_MS);
        }
    }

    /**
     * Record a successful provisioning in this DC.
     * Resets the circuit breaker to CLOSED regardless of current state.
     */
    synchronized void recordSuccess() {
        State previous = state;
        consecutiveFailures = 0;
        state = State.CLOSED;
        lastSuccessAt = System.currentTimeMillis();
        if (previous != State.CLOSED) {
            log.info("DC {} arch {} circuit breaker: {} -> CLOSED (provisioning succeeded)",
                    location, arch, previous);
            recordTransition(previous, State.CLOSED);
        }
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location, arch).set(0);
    }

    /**
     * Record a failed provisioning in this DC.
     * After FAILURE_THRESHOLD consecutive failures, opens the circuit breaker.
     */
    synchronized void recordFailure() {
        consecutiveFailures++;
        lastFailureAt = System.currentTimeMillis();
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location, arch).set(consecutiveFailures);
        if (state == State.HALF_OPEN) {
            // Probe failed, go back to OPEN
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} arch {} circuit breaker: HALF_OPEN -> OPEN (probe failed, {} consecutive failures)",
                    location, arch, consecutiveFailures);
            recordTransition(State.HALF_OPEN, State.OPEN);
        } else if (consecutiveFailures >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} arch {} circuit breaker: CLOSED -> OPEN ({} consecutive failures)",
                    location, arch, consecutiveFailures);
            recordTransition(State.CLOSED, State.OPEN);
        } else {
            log.info("DC {} arch {} provisioning failed ({}/{} before circuit opens)",
                    location, arch, consecutiveFailures, FAILURE_THRESHOLD);
        }
    }

    synchronized State getState() {
        // Re-evaluate in case reset timeout elapsed.
        //
        // Intentionally does NOT emit DC_BREAKER_TRANSITIONS counter here.
        // getState() is called by getters (Script Console, tests, dashboards)
        // and would inflate the counter every time the timeout has elapsed
        // until isHealthy() actually transitions. We update only the gauge
        // so the state stays consistent with what callers observe; the
        // transition counter is bumped exactly once when isHealthy() drives
        // the state change.
        if (state == State.OPEN && System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
            state = State.HALF_OPEN;
            // v103.percona.26: arm probe lease on lazy-reset too, so a
            // subsequent isProbeable()/tryAcquireProbe() in this HALF_OPEN
            // window can consume it.
            halfOpenProbeAvailable = true;
            halfOpenEnteredAt = System.currentTimeMillis();
            HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(State.HALF_OPEN.ordinal());
        }
        return state;
    }

    synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    synchronized long getLastSuccessAt() {
        return lastSuccessAt;
    }

    synchronized long getLastFailureAt() {
        return lastFailureAt;
    }

    /**
     * Post-deserialization hook called from {@link DcHealthTracker#load()}.
     * Restores the Prometheus gauges that are not persisted (so dashboards
     * render the loaded state right after master boot) and applies the
     * stale-OPEN TTL so an old transient outage does not pin a DC out of
     * rotation after a long restart.
     *
     * <p>{@code fallbackArch} fills in this field for pre-v25 XML where
     * the breaker map was keyed solely by location and the breaker itself
     * had no arch coordinate. {@link DcHealthTracker#load()} synthesizes
     * one breaker per arch from each legacy entry and feeds the chosen
     * arch here.
     */
    synchronized void afterLoad(String fallbackLocation, String fallbackArch,
                                long now, long staleOpenTtlMs) {
        if (location == null) {
            location = fallbackLocation;
        }
        if (arch == null) {
            arch = fallbackArch;
        }
        if (state == State.OPEN && now - openedAt >= staleOpenTtlMs) {
            log.info("DC {} arch {} circuit breaker: OPEN -> CLOSED on load (stale, last failure {}ms ago)",
                    location, arch, now - openedAt);
            state = State.CLOSED;
            consecutiveFailures = 0;
            openedAt = 0;
            HetznerMetricProvider.DC_HEALTH_STALE_OPEN_RESETS.labels(location, arch).inc();
        }
        HetznerMetricProvider.DC_BREAKER_STATE.labels(location, arch).set(state.ordinal());
        HetznerMetricProvider.DC_BREAKER_CONSECUTIVE_FAILURES.labels(location, arch).set(consecutiveFailures);
    }

    /** Visible for testing. */
    static int failureThreshold() {
        return FAILURE_THRESHOLD;
    }

    /** Visible for testing. */
    static long resetTimeoutMs() {
        return RESET_TIMEOUT_MS;
    }
}
