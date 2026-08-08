package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure-JDK cancellable transport used below CDN selection and redirect policy. */
public final class CancellableHttpTransport {
    private CancellableHttpTransport() {
    }

    public static Response send(HttpClient client, HttpRequest request, HttpRequestCloseDiagnostics diagnostics,
            long operationId) throws IOException {
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(
                request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = future.get();
        } catch (InterruptedException e) {
            diagnostics.cancelRequested(operationId);
            closeLateResponse(future);
            future.cancel(true);
            diagnostics.terminal(operationId, false, 0L, System.nanoTime());
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", e);
        } catch (ExecutionException e) {
            diagnostics.terminal(operationId, false, 0L, System.nanoTime());
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("HTTP request failed", cause);
        }
        diagnostics.headers(operationId, response.statusCode());
        diagnostics.bodyPublished(operationId);
        return new Response(response.statusCode(), response.headers(),
                new DiagnosticInputStream(response.body(), diagnostics, operationId));
    }

    private static void closeLateResponse(CompletableFuture<HttpResponse<InputStream>> future) {
        future.whenComplete((lateResponse, failure) -> {
            if (lateResponse != null) {
                try {
                    lateResponse.body().close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    public record Response(int statusCode, HttpHeaders headers, InputStream body) {
    }

    private static final class DiagnosticInputStream extends FilterInputStream {
        private final HttpRequestCloseDiagnostics diagnostics;
        private final long operationId;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private long bytes;
        private boolean eof;

        private DiagnosticInputStream(InputStream delegate, HttpRequestCloseDiagnostics diagnostics,
                long operationId) {
            super(delegate);
            this.diagnostics = diagnostics;
            this.operationId = operationId;
        }

        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) complete(true); else bytes++;
                return value;
            } catch (IOException e) {
                complete(false);
                throw e;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int count = super.read(buffer, offset, length);
                if (count < 0) complete(true); else bytes += count;
                return count;
            } catch (IOException e) {
                complete(false);
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            if (!eof) diagnostics.cancelRequested(operationId);
            try {
                super.close();
                complete(true);
            } catch (IOException e) {
                complete(false);
                throw e;
            }
        }

        private void complete(boolean success) {
            if (terminal.compareAndSet(false, true)) {
                eof = success;
                diagnostics.terminal(operationId, success, bytes, System.nanoTime());
            }
        }
    }
}