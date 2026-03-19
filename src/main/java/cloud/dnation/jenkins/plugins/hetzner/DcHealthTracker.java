/*
 * Static registry of per-DC circuit breakers.
 * Provides sorted template lists that prefer healthy DCs while maintaining
 * backward-compatible random selection when all DCs are healthy.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
class DcHealthTracker {

    private static final ConcurrentHashMap<String, DcCircuitBreaker> BREAKERS = new ConcurrentHashMap<>();

    private DcHealthTracker() {
    }

    /**
     * Get or create the circuit breaker for a given DC location.
     */
    static DcCircuitBreaker getBreaker(String location) {
        return BREAKERS.computeIfAbsent(location, DcCircuitBreaker::new);
    }

    /**
     * Record a provisioning failure for the given DC.
     */
    static void recordFailure(String location) {
        getBreaker(location).recordFailure();
    }

    /**
     * Record a provisioning success for the given DC.
     */
    static void recordSuccess(String location) {
        getBreaker(location).recordSuccess();
    }

    /**
     * Check if a DC is currently considered healthy.
     */
    static boolean isHealthy(String location) {
        return getBreaker(location).isHealthy();
    }

    /**
     * Sort templates by DC health: healthy DCs first, unhealthy last.
     * Within each partition, templates are shuffled randomly.
     * When all DCs are healthy (normal case), this is equivalent to a random shuffle.
     *
     * @param templates list of matching templates
     * @return new list sorted by DC health (never modifies input)
     */
    static List<HetznerServerTemplate> sortByHealth(List<HetznerServerTemplate> templates) {
        if (templates == null || templates.size() <= 1) {
            return templates == null ? Collections.emptyList() : new ArrayList<>(templates);
        }

        List<HetznerServerTemplate> healthy = templates.stream()
                .filter(t -> isHealthy(t.getLocation()))
                .collect(Collectors.toCollection(ArrayList::new));

        List<HetznerServerTemplate> unhealthy = templates.stream()
                .filter(t -> !isHealthy(t.getLocation()))
                .collect(Collectors.toCollection(ArrayList::new));

        Collections.shuffle(healthy);
        Collections.shuffle(unhealthy);

        if (!unhealthy.isEmpty()) {
            log.info("DC health ranking: {} healthy, {} unhealthy DCs for {} templates",
                    healthy.size(), unhealthy.size(), templates.size());
        }

        List<HetznerServerTemplate> ranked = new ArrayList<>(templates.size());
        ranked.addAll(healthy);
        ranked.addAll(unhealthy);
        return ranked;
    }

    /**
     * Get a snapshot of all tracked breakers. For observability/testing.
     */
    static ConcurrentHashMap<String, DcCircuitBreaker> getAllBreakers() {
        return BREAKERS;
    }

    /**
     * Reset all circuit breakers. For testing only.
     */
    static void resetAll() {
        BREAKERS.clear();
    }
}
