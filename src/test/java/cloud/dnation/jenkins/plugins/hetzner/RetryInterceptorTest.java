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

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryInterceptorTest {

    private static final Request DUMMY_REQUEST = new Request.Builder()
            .url("https://api.hetzner.cloud/v1/servers")
            .build();

    // Use baseMs=1, capMs=1 for instant retries in tests
    private final RetryInterceptor interceptor = new RetryInterceptor("test-cred", 1, 1);

    @Test
    void doesNotRetry_429() throws Exception {
        Interceptor.Chain chain = mockChain(responseWithCode(429));
        Response result = interceptor.intercept(chain);
        assertEquals(429, result.code());
        verify(chain, times(1)).proceed(any());
    }

    @Test
    void doesNotRetry_401() throws Exception {
        Interceptor.Chain chain = mockChain(responseWithCode(401));
        Response result = interceptor.intercept(chain);
        assertEquals(401, result.code());
        verify(chain, times(1)).proceed(any());
    }

    @Test
    void doesNotRetry_404() throws Exception {
        Interceptor.Chain chain = mockChain(responseWithCode(404));
        Response result = interceptor.intercept(chain);
        assertEquals(404, result.code());
        verify(chain, times(1)).proceed(any());
    }

    @Test
    void doesNotRetry_500() throws Exception {
        Interceptor.Chain chain = mockChain(responseWithCode(500));
        Response result = interceptor.intercept(chain);
        assertEquals(500, result.code());
        verify(chain, times(1)).proceed(any());
    }

    @Test
    void retries_502() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        when(chain.proceed(any()))
                .thenReturn(responseWithCode(502))
                .thenReturn(responseWithCode(200));

        Response result = interceptor.intercept(chain);
        assertEquals(200, result.code());
        verify(chain, times(2)).proceed(any());
    }

    @Test
    void retries_504() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        when(chain.proceed(any()))
                .thenReturn(responseWithCode(504))
                .thenReturn(responseWithCode(200));

        Response result = interceptor.intercept(chain);
        assertEquals(200, result.code());
        verify(chain, times(2)).proceed(any());
    }

    @Test
    void retries_502_exhaustsMaxRetries() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        // Always returns 502: 1 initial + 3 retries = 4 calls
        when(chain.proceed(any())).thenReturn(responseWithCode(502));

        Response result = interceptor.intercept(chain);
        assertEquals(502, result.code());
        verify(chain, times(4)).proceed(any());
    }

    @Test
    void retries_socketTimeout() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        when(chain.proceed(any()))
                .thenThrow(new SocketTimeoutException("connect timed out"))
                .thenReturn(responseWithCode(200));

        Response result = interceptor.intercept(chain);
        assertEquals(200, result.code());
        verify(chain, times(2)).proceed(any());
    }

    @Test
    void doesNotRetry_interruptedIOException() throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        when(chain.proceed(any())).thenThrow(new InterruptedIOException("interrupted"));

        assertThrows(InterruptedIOException.class, () -> interceptor.intercept(chain));
        verify(chain, times(1)).proceed(any());
    }

    @Test
    void passesThrough_200() throws Exception {
        Interceptor.Chain chain = mockChain(responseWithCode(200));
        Response result = interceptor.intercept(chain);
        assertEquals(200, result.code());
        verify(chain, times(1)).proceed(any());
    }

    // --- Helpers ---

    private static Interceptor.Chain mockChain(Response response) throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(DUMMY_REQUEST);
        when(chain.proceed(any())).thenReturn(response);
        return chain;
    }

    private static Response responseWithCode(int code) {
        return new Response.Builder()
                .request(DUMMY_REQUEST)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("mock")
                .body(ResponseBody.create("", MediaType.get("application/json")))
                .build();
    }
}
