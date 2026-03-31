/*
 * Typed exception for Hetzner API provisioning failures.
 * Carries HTTP status code, Hetzner error code, and DC location
 * to enable intelligent retry decisions in NodeCallable.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import lombok.Getter;

@Getter
class HetznerProvisioningException extends RuntimeException {

    private final int httpStatus;
    private final String hetznerErrorCode;
    private final String location;

    HetznerProvisioningException(String message, int httpStatus, String hetznerErrorCode, String location) {
        super(message);
        this.httpStatus = httpStatus;
        this.hetznerErrorCode = hetznerErrorCode;
        this.location = location;
    }

    HetznerProvisioningException(String message, int httpStatus, String hetznerErrorCode,
                                  String location, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.hetznerErrorCode = hetznerErrorCode;
        this.location = location;
    }

    /**
     * Whether this error indicates DC resource unavailability (should retry in another DC).
     * Hetzner returns HTTP 422 with error code "resource_unavailable" when a DC cannot
     * fulfill the server type request. Also matches "placement_error" and "server_limit_exceeded".
     */
    /**
     * Whether this error is a rate-limit (HTTP 429) response.
     * Rate-limiting is token-scoped, not DC-scoped: all DCs share the same
     * API token, so retrying in another DC will not help.
     */
    boolean isRateLimited() {
        return httpStatus == 429 || "rate_limit_exceeded".equals(hetznerErrorCode);
    }

    /**
     * Whether this error indicates a persistent template configuration problem.
     * These errors won't resolve with DC failover or retries; they require
     * a config change (e.g., updating a deprecated image ID).
     */
    boolean isConfigError() {
        return "invalid_input".equals(hetznerErrorCode);
    }

    boolean isResourceUnavailable() {
        if (httpStatus == 422) {
            return true;
        }
        if (hetznerErrorCode == null) {
            return false;
        }
        return "resource_unavailable".equals(hetznerErrorCode)
                || "placement_error".equals(hetznerErrorCode)
                || "server_limit_exceeded".equals(hetznerErrorCode);
    }
}
