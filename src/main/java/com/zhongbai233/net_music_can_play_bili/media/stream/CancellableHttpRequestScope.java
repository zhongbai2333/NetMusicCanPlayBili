package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the root {@link HttpClient#sendAsync} futures for one API/UI lifetime.
 * Closing the scope cancels every in-flight request and prevents late admission.
 */
public final class CancellableHttpRequestScope implements AutoCloseable {
    private final HttpRequestCloseDiagnostics diagnostics;
    private final ConcurrentHashMap<CompletableFuture<?>, Long> requests = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public CancellableHttpRequestScope(HttpRequestCloseDiagnostics diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpClient client, HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler, String kind) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(bodyHandler, "bodyHandler");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new CancellationException("HTTP request scope is closed"));
        }
        long operationId = diagnostics.begin(kind, safeHost(request.uri()), -1L, -1L, System.nanoTime());
        CompletableFuture<HttpResponse<T>> future;
        try {
            future = client.sendAsync(request, bodyHandler);
        } catch (RuntimeException | Error error) {
            diagnostics.terminal(operationId, false, 0L, System.nanoTime());
            throw error;
        }
        boolean cancelImmediately;
        synchronized (lifecycleLock) {
            cancelImmediately = closed.get();
            if (!cancelImmediately) {
                requests.put(future, operationId);
            }
        }
        future.whenComplete((response, error) -> {
            try {
                if (response != null) {
                    diagnostics.headers(operationId, response.statusCode());
                    diagnostics.bodyPublished(operationId);
                }
                diagnostics.terminal(operationId, error == null, responseBytes(response), System.nanoTime());
            } finally {
                requests.remove(future, operationId);
            }
        });
        if (cancelImmediately) {
            cancel(future, operationId);
        }
        return future;
    }

    public <T> HttpResponse<T> sendBlocking(HttpClient client, HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler, String kind) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> future = sendAsync(client, request, bodyHandler, kind);
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Long operationId = requests.get(future);
            if (operationId != null) {
                cancel(future, operationId);
            } else {
                future.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("HTTP request failed", cause);
        }
    }

    public static <T> HttpResponse<T> sendOneBlocking(HttpClient client, HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler, HttpRequestCloseDiagnostics diagnostics, String kind)
            throws IOException, InterruptedException {
        try (CancellableHttpRequestScope scope = new CancellableHttpRequestScope(diagnostics)) {
            return scope.sendBlocking(client, request, bodyHandler, kind);
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public int activeRequests() {
        return requests.size();
    }

    @Override
    public void close() {
        Map<CompletableFuture<?>, Long> toCancel;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            toCancel = new HashMap<>(requests);
            // Ownership ends synchronously with close(); completion callbacks may run later.
            requests.clear();
        }
        toCancel.forEach((future, operationId) -> cancel(future, operationId.longValue()));
    }

    private void cancel(CompletableFuture<?> future, long operationId) {
        diagnostics.cancelRequested(operationId);
        future.cancel(true);
    }

    private static long responseBytes(HttpResponse<?> response) {
        if (response == null || response.body() == null) {
            return 0L;
        }
        Object body = response.body();
        if (body instanceof byte[] bytes) {
            return bytes.length;
        }
        if (body instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8).length;
        }
        return 0L;
    }

    private static String safeHost(URI uri) {
        String host = uri != null ? uri.getHost() : null;
        return host == null || host.isBlank() ? "<unknown>" : host;
    }
}
