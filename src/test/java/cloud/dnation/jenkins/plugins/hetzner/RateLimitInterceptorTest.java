/*
 * Copyright 2026 Percona LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 */
package cloud.dnation.jenkins.plugins.hetzner;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitInterceptorTest {

    private static final Request DUMMY_REQUEST = new Request.Builder()
            .url("https://api.hetzner.cloud/v1/servers")
            .build();

    @Test
    void parsesRateLimitHeaders() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(200)
                .addHeader("RateLimit-Limit", "3600")
                .addHeader("RateLimit-Remaining", "2500")
                .build();

        Interceptor.Chain chain = mockChain(response);
        interceptor.intercept(chain);

        verify(apiClient).updateRateLimitState(3600, 2500);
    }

    @Test
    void handles429WithRetryAfter() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(429)
                .addHeader("Retry-After", "45")
                .addHeader("RateLimit-Remaining", "0")
                .build();

        Interceptor.Chain chain = mockChain(response);
        interceptor.intercept(chain);

        verify(apiClient).recordRateLimit(45);
    }

    @Test
    void handles429WithoutRetryAfter() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(429).build();
        Interceptor.Chain chain = mockChain(response);
        interceptor.intercept(chain);

        // Default 60s when no Retry-After header
        verify(apiClient).recordRateLimit(60);
    }

    @Test
    void handles401InvalidatesClient() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(401).build();
        Interceptor.Chain chain = mockChain(response);
        interceptor.intercept(chain);

        verify(apiClient).invalidate();
    }

    @Test
    void normalResponse_noInvalidate() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(200).build();
        Interceptor.Chain chain = mockChain(response);
        interceptor.intercept(chain);

        verify(apiClient, never()).invalidate();
    }

    @Test
    void normalResponse_noRecordRateLimit() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(200).build();
        Interceptor.Chain chain = mockChain(response);
        interceptor.intercept(chain);

        verify(apiClient, never()).recordRateLimit(anyLong());
    }

    @Test
    void malformedHeaders_noException() throws Exception {
        HetznerApiClient apiClient = mock(HetznerApiClient.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(apiClient);

        Response response = responseBuilder(200)
                .addHeader("RateLimit-Limit", "not-a-number")
                .addHeader("RateLimit-Remaining", "")
                .build();

        Interceptor.Chain chain = mockChain(response);
        // Should not throw
        interceptor.intercept(chain);

        // Should call with defaults (-1, -1) since parsing failed
        verify(apiClient).updateRateLimitState(-1, -1);
    }

    // --- Helpers ---

    private static Interceptor.Chain mockChain(Response response) throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        when(chain.proceed(any())).thenReturn(response);
        return chain;
    }

    private static Response.Builder responseBuilder(int code) {
        return new Response.Builder()
                .request(DUMMY_REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(ResponseBody.create("", MediaType.get("application/json")));
    }
}
