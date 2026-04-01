/*
 * DC-level circuit breaker for Hetzner Cloud provisioning.
 * Tracks consecutive failures per datacenter location and short-circuits
 * provisioning attempts to broken DCs, forcing failover to healthy ones.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class DcCircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private static final int FAILURE_THRESHOLD = 2;
    private static final long RESET_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes

    @Getter
    private final String location;
    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0;
    private long lastSuccessAt = System.currentTimeMillis();
    private long lastFailureAt = 0;

    DcCircuitBreaker(String location) {
        this.location = location;
    }

    /**
     * Check if this DC should be attempted for provisioning.
     * CLOSED: always yes.
     * OPEN: no, unless reset timeout has elapsed (transitions to HALF_OPEN).
     * HALF_OPEN: yes (one probe attempt allowed).
     */
    synchronized boolean isHealthy() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
                state = State.HALF_OPEN;
                log.info("DC {} circuit breaker: OPEN -> HALF_OPEN (reset timeout elapsed)", location);
                return true;
            }
            return false;
        }
        // HALF_OPEN: allow one probe
        return true;
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
            log.info("DC {} circuit breaker: {} -> CLOSED (provisioning succeeded)", location, previous);
        }
    }

    /**
     * Record a failed provisioning in this DC.
     * After FAILURE_THRESHOLD consecutive failures, opens the circuit breaker.
     */
    synchronized void recordFailure() {
        consecutiveFailures++;
        lastFailureAt = System.currentTimeMillis();
        if (state == State.HALF_OPEN) {
            // Probe failed, go back to OPEN
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} circuit breaker: HALF_OPEN -> OPEN (probe failed, {} consecutive failures)",
                    location, consecutiveFailures);
        } else if (consecutiveFailures >= FAILURE_THRESHOLD) {
            state = State.OPEN;
            openedAt = System.currentTimeMillis();
            log.warn("DC {} circuit breaker: CLOSED -> OPEN ({} consecutive failures)",
                    location, consecutiveFailures);
        } else {
            log.info("DC {} provisioning failed ({}/{} before circuit opens)",
                    location, consecutiveFailures, FAILURE_THRESHOLD);
        }
    }

    synchronized State getState() {
        // Re-evaluate in case reset timeout elapsed
        if (state == State.OPEN && System.currentTimeMillis() - openedAt >= RESET_TIMEOUT_MS) {
            state = State.HALF_OPEN;
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

    /** Visible for testing. */
    static int failureThreshold() {
        return FAILURE_THRESHOLD;
    }

    /** Visible for testing. */
    static long resetTimeoutMs() {
        return RESET_TIMEOUT_MS;
    }
}
