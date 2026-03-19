// DC Circuit Breaker Health Check
// Run via Jenkins Script Console to inspect DC health state.
// Usage: jenkins admin -i <inst> groovy -f scripts/dc-health-check.groovy

import cloud.dnation.jenkins.plugins.hetzner.DcHealthTracker

def breakers = DcHealthTracker.getAllBreakers()

if (breakers.isEmpty()) {
    println "No DC circuit breakers registered (no provisioning attempts yet)"
    return
}

println "DC Circuit Breaker Status"
println "=" * 60

breakers.sort { it.key }.each { location, cb ->
    def state = cb.getState()
    def failures = cb.getConsecutiveFailures()
    def lastSuccess = cb.getLastSuccessAt()
    def lastFailure = cb.getLastFailureAt()

    def lastSuccessStr = lastSuccess > 0
        ? new Date(lastSuccess).format("yyyy-MM-dd HH:mm:ss z")
        : "never"
    def lastFailureStr = lastFailure > 0
        ? new Date(lastFailure).format("yyyy-MM-dd HH:mm:ss z")
        : "never"

    def icon = state.name() == "CLOSED" ? "OK" : (state.name() == "OPEN" ? "OPEN" : "PROBE")

    println String.format("  %-8s [%-5s] failures=%d  last_success=%s  last_failure=%s",
        location, icon, failures, lastSuccessStr, lastFailureStr)
}
println "=" * 60
