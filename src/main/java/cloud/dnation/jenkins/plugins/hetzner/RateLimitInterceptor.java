/*
 * OkHttp interceptor that reads Hetzner rate-limit headers from every API
 * response and feeds them back to the owning HetznerApiClient.
 *
 * Runs transparently on ALL API calls, including paginated fetches inside
 * PagedResourceHelper that are unreachable from plugin code.
 *
 * Headers parsed (per https://docs.hetzner.cloud and hcloud-go reference):
 *   RateLimit-Limit     – total requests allowed per window (e.g. 3600)
 *   RateLimit-Remaining – requests remaining in current window
 *   RateLimit-Reset     – unix epoch (seconds) when the window resets
 */
package cloud.dnation.jenkins.plugins.hetzner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
class RateLimitInterceptor implements Interceptor {

    private final HetznerApiClient apiClient;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());

        int limit = parseIntHeader(response, "RateLimit-Limit", -1);
        int remaining = parseIntHeader(response, "RateLimit-Remaining", -1);

        apiClient.updateRateLimitState(limit, remaining);

        if (response.code() == 429) {
            long retryAfter = parseLongHeader(response, "Retry-After", 0);
            log.warn("HTTP 429 on {} {} (remaining={}, retryAfter={}s)",
                    chain.request().method(), chain.request().url().encodedPath(),
                    remaining, retryAfter > 0 ? retryAfter : "default-60");
            apiClient.recordRateLimit(retryAfter > 0 ? retryAfter : 60);
        } else if (response.code() == 401) {
            log.warn("HTTP 401 on {} {} -- token may have been rotated, invalidating client",
                    chain.request().method(), chain.request().url().encodedPath());
            apiClient.invalidate();
        }

        return response;
    }

    private static int parseIntHeader(Response response, String name, int defaultValue) {
        String value = response.header(name);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                log.debug("Failed to parse header {}={}", name, value);
            }
        }
        return defaultValue;
    }

    private static long parseLongHeader(Response response, String name, long defaultValue) {
        String value = response.header(name);
        if (value != null) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                log.debug("Failed to parse header {}={}", name, value);
            }
        }
        return defaultValue;
    }
}
