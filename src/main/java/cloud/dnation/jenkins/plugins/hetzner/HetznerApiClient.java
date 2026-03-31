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
                    String token = JenkinsSecretTokenProvider.forCredentialsId(credentialsId).get();

                    HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(log::debug);
                    loggingInterceptor.redactHeader("Authorization");
                    loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

                    OkHttpClient httpClient = new OkHttpClient.Builder()
                            .connectionPool(CONNECTION_POOL)
                            .addInterceptor(new AuthInterceptor(token))
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

    void updateRateLimitState(int httpStatus, int remaining, long resetEpoch) {
        if (remaining >= 0) {
            int prev = this.remaining.getAndSet(remaining);
            // Log when quota drops below 10% of limit (360 of 3600)
            if (remaining <= 360 && remaining < prev) {
                log.warn("Hetzner API quota low: {}/3600 remaining (credentialsId={})", remaining, credentialsId);
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

    /** Visible for testing. */
    static void resetAll() {
        INSTANCES.invalidateAll();
    }

    /**
     * Minimal auth interceptor: adds Bearer token header.
     * Replaces upstream AuthenticationInterceptor to avoid depending
     * on its package-private visibility.
     */
    private static class AuthInterceptor implements okhttp3.Interceptor {
        private final String token;

        AuthInterceptor(String token) {
            this.token = token;
        }

        @Override
        public okhttp3.Response intercept(Chain chain) throws java.io.IOException {
            return chain.proceed(chain.request().newBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", "hetzner-cloud-plugin/jenkins")
                    .build());
        }
    }
}
