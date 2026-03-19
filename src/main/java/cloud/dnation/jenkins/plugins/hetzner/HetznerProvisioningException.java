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
