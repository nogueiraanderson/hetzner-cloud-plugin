/*
 * Per-token Hetzner API client wrapper with rate-limit awareness.
 *
 * Replaces the upstream ClientFactory.create() with a custom Retrofit build
 * that includes a RateLimitInterceptor on every API call. Singleton per
 * credentialsId (one per Jenkins cloud configuration).
 *
 * Rate-limit state is token-scoped: when HTTP 429 is received, ALL API calls
 * for this token are blocked until the Hetzner rate-limit window resets.
 * This prevents the feedback loop where failed retries deepen the penalty.
 */
package cloud.dnation.jenkins.plugins.hetzner;

import cloud.dnation.hetznerclient.HetznerApi;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
class HetznerApiClient {

    private static final String BASE_URL = System.getProperty(
            "cloud.dnation.hetznerclient.apiendpoint", "https://api.hetzner.cloud/v1/");

    private static final ConnectionPool CONNECTION_POOL = new ConnectionPool();

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    // One instance per credentialsId, evicted after 1 hour idle
    private static final Cache<String, HetznerApiClient> INSTANCES =
            CacheBuilder.newBuilder()
                    .expireAfterAccess(1, TimeUnit.HOURS)
                    .build();

    private final String credentialsId;
    private volatile HetznerApi api;

    // Rate-limit tracking (token-scoped)
    private final AtomicInteger limit = new AtomicInteger(3600);
    private final AtomicInteger remaining = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicReference<Instant> resetAt = new AtomicReference<>(Instant.EPOCH);
    private volatile boolean rateLimited = false;

    private HetznerApiClient(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    static HetznerApiClient forCredentials(String credentialsId) {
        try {
            return INSTANCES.get(credentialsId, () -> {
                log.info("Creating HetznerApiClient for credentialsId={}", credentialsId);
                return new HetznerApiClient(credentialsId);
            });
        } catch (Exception e) {
            log.warn("Failed to get cached HetznerApiClient for credentialsId={}, creating uncached instance",
                    credentialsId, e);
            return new HetznerApiClient(credentialsId);
        }
    }

    /**
     * Build and cache the Retrofit HetznerApi proxy.
     * Replicates the upstream ClientFactory.create() chain but adds
     * our RateLimitInterceptor to the OkHttp pipeline.
     */
    HetznerApi proxy() {
        if (api == null) {
            synchronized (this) {
                if (api == null) {
                    String logLevel = System.getProperty(
                            "cloud.dnation.hetzner.http.loglevel", "BASIC");
                    HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(log::debug);
                    loggingInterceptor.redactHeader("Authorization");
                    loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.valueOf(logLevel));

                    OkHttpClient httpClient = new OkHttpClient.Builder()
                            .connectionPool(CONNECTION_POOL)
                            .addInterceptor(new AuthInterceptor(credentialsId))
                            .addInterceptor(new RetryInterceptor(credentialsId))
                            .addInterceptor(new RateLimitInterceptor(this))
                            .addInterceptor(loggingInterceptor)
                            .build();

                    api = new Retrofit.Builder()
                            .baseUrl(BASE_URL)
                            .client(httpClient)
                            .addConverterFactory(GsonConverterFactory.create(GSON))
                            .build()
                            .create(HetznerApi.class);
                }
            }
        }
        return api;
    }

    boolean isRateLimited() {
        if (!rateLimited) {
            return false;
        }
        if (Instant.now().isAfter(resetAt.get())) {
            rateLimited = false;
            log.info("Token rate-limit cleared, resuming API calls (credentialsId={})", credentialsId);
            return false;
        }
        return true;
    }

    Duration timeUntilReset() {
        if (!isRateLimited()) {
            return Duration.ZERO;
        }
        Duration d = Duration.between(Instant.now(), resetAt.get());
        return d.isNegative() ? Duration.ZERO : d;
    }

    void updateRateLimitState(int httpStatus, int limit, int remaining, long resetEpoch) {
        if (limit > 0) {
            this.limit.set(limit);
        }
        if (remaining >= 0) {
            int prev = this.remaining.getAndSet(remaining);
            int currentLimit = this.limit.get();
            int threshold = currentLimit / 10; // 10% of limit
            if (remaining <= threshold && remaining < prev) {
                log.warn("Hetzner API quota low: {}/{} remaining (credentialsId={})",
                        remaining, currentLimit, credentialsId);
            }
        }
        if (resetEpoch > 0) {
            this.resetAt.set(Instant.ofEpochSecond(resetEpoch));
        }
    }

    void recordRateLimit(long retryAfterSeconds) {
        rateLimited = true;
        Instant reset = retryAfterSeconds > 0
                ? Instant.now().plusSeconds(retryAfterSeconds)
                : Instant.now().plusSeconds(60);
        resetAt.set(reset);
        log.warn("Token rate-limited (credentialsId={}). Blocking API calls for {}s (until {})",
                credentialsId, retryAfterSeconds > 0 ? retryAfterSeconds : 60, reset);
    }

    int getRemaining() {
        return remaining.get();
    }

    /**
     * Invalidate this client so the next proxy() call rebuilds with fresh credentials.
     * Called on HTTP 401 (token may have been rotated in Jenkins).
     */
    void invalidate() {
        synchronized (this) {
            api = null;
        }
        INSTANCES.invalidate(credentialsId);
        log.info("HetznerApiClient invalidated for credentialsId={} (will rebuild on next use)", credentialsId);
    }

    /** Visible for testing. */
    static void resetAll() {
        INSTANCES.invalidateAll();
    }

    /**
     * Auth interceptor that resolves the token lazily on every request.
     * Uses JenkinsSecretTokenProvider (in-memory credential lookup) so
     * token rotations are picked up immediately without cache invalidation.
     */
    private static class AuthInterceptor implements okhttp3.Interceptor {
        private final String credentialsId;

        AuthInterceptor(String credentialsId) {
            this.credentialsId = credentialsId;
        }

        @Override
        public okhttp3.Response intercept(Chain chain) throws java.io.IOException {
            String token = JenkinsSecretTokenProvider.forCredentialsId(credentialsId).get();
            return chain.proceed(chain.request().newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "hetzner-cloud-plugin/jenkins")
                    .build());
        }
    }
}
