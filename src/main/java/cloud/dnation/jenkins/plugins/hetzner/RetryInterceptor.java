/*
 * OkHttp interceptor implementing retry with exponential backoff and jitter
 * for transient Hetzner API errors.
 *
 * Retry policy:
 *   Retried:     502, 504, network timeouts
 *   Not retried: 401, 403, 404, 409, 422, 429, 500, 503, and all other codes
 *   Note: 429 is handled by RateLimitInterceptor (token-scoped block), not retried here.
 *
 * Backoff formula (AWS full jitter):
 *   raw   = base * multiplier^attempt
 *   cap   = min(maxDelay, raw)
 *   delay = base + random * (cap - base)
 *
 * For 429 responses, the delay is max(calculated_backoff, Retry-After header).
 */
package cloud.dnation.jenkins.plugins.hetzner;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
class RetryInterceptor implements Interceptor {

    private static final int MAX_RETRIES = 3;
    private static final long BASE_MS = 1_000;
    private static final double MULTIPLIER = 2.0;
    private static final long CAP_MS = 30_000;
    // 429 is NOT retried here; RateLimitInterceptor handles it by blocking
    // further API calls until the rate-limit window resets.
    private static final Set<Integer> RETRYABLE_CODES = Set.of(502, 504);

    private final String credentialsId;

    RetryInterceptor(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            // Close previous response body before retry to avoid connection leak
            if (response != null) {
                response.close();
            }

            try {
                response = chain.proceed(attempt == 0 ? request : request.newBuilder().build());

                if (!isRetryable(response.code()) || attempt == MAX_RETRIES) {
                    return response;
                }

                // Retryable status code -- calculate backoff
                long delay = calculateDelay(attempt);
                log.warn("HTTP {} on {} {} [{}], retrying in {}ms (attempt {}/{})",
                        response.code(),
                        request.method(), request.url().encodedPath(),
                        credentialsId, delay, attempt + 1, MAX_RETRIES);
                sleep(delay);

            } catch (SocketTimeoutException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) {
                    break;
                }
                long delay = calculateDelay(attempt);
                log.warn("Timeout on {} {} [{}], retrying in {}ms (attempt {}/{}): {}",
                        request.method(), request.url().encodedPath(),
                        credentialsId, delay, attempt + 1, MAX_RETRIES, e.getMessage());
                sleep(delay);
            } catch (InterruptedIOException e) {
                // Thread interrupted (not a timeout) -- do not retry
                throw e;
            }
        }

        // Exhausted retries on network timeout
        if (lastException != null) {
            log.error("Exhausted {} retries on {} {} [{}] due to timeouts",
                    MAX_RETRIES, request.method(), request.url().encodedPath(), credentialsId);
            throw lastException;
        }

        // Should not reach here, but return last response as safety net
        return response;
    }

    private static boolean isRetryable(int code) {
        return RETRYABLE_CODES.contains(code);
    }

    /**
     * Exponential backoff with full jitter (AWS-style).
     */
    private static long calculateDelay(int attempt) {
        double raw = BASE_MS * Math.pow(MULTIPLIER, attempt);
        long capped = Math.min(CAP_MS, (long) raw);
        return BASE_MS + (long) (ThreadLocalRandom.current().nextDouble() * (capped - BASE_MS));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
