package com.zhongbai233.net_music_can_play_bili.bench;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loopback byte-range origin for the repository's exact real-Bilibili AV1 fixture. */
final class FrozenRealAv1RangeServer implements AutoCloseable {
    static final long SEEK_FRAGMENT_START = 900_893L;
    private static final int VIRTUAL_LENGTH = 962_852;
    private static final String FIRST_RESOURCE = "/bili/real-av1/init-index-first-fragment.m4s.b64";
    private static final String SEEK_RESOURCE = "/bili/real-av1/seek-fragment-35s.m4s.b64";
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private final byte[] bytes;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicInteger fullRequests = new AtomicInteger();
    private final AtomicInteger rangeRequests = new AtomicInteger();
    private final ConcurrentLinkedQueue<Long> rangeStarts = new ConcurrentLinkedQueue<>();

    private FrozenRealAv1RangeServer(byte[] bytes, HttpServer server, ExecutorService executor) {
        this.bytes = bytes;
        this.server = server;
        this.executor = executor;
    }

    static FrozenRealAv1RangeServer start() throws IOException {
        byte[] bytes = new byte[VIRTUAL_LENGTH];
        byte[] first = fixture(FIRST_RESOURCE);
        byte[] seek = fixture(SEEK_RESOURCE);
        if (first.length != 117_150 || seek.length != 61_959
                || SEEK_FRAGMENT_START + seek.length != VIRTUAL_LENGTH) {
            throw new IOException("unexpected frozen AV1 fixture layout");
        }
        System.arraycopy(first, 0, bytes, 0, first.length);
        int fillerLength = Math.toIntExact(SEEK_FRAGMENT_START - first.length);
        if (fillerLength < 8) {
            throw new IOException("frozen AV1 fixture has no room for the filler box");
        }
        // Keep the virtual representation parseable on a full GET while preserving
        // the exact SIDX byte offset of the independently frozen 35-second fragment.
        bytes[first.length] = (byte) (fillerLength >>> 24);
        bytes[first.length + 1] = (byte) (fillerLength >>> 16);
        bytes[first.length + 2] = (byte) (fillerLength >>> 8);
        bytes[first.length + 3] = (byte) fillerLength;
        bytes[first.length + 4] = 'f';
        bytes[first.length + 5] = 'r';
        bytes[first.length + 6] = 'e';
        bytes[first.length + 7] = 'e';
        System.arraycopy(seek, 0, bytes, (int) SEEK_FRAGMENT_START, seek.length);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ncpb-frozen-av1-range");
            thread.setDaemon(true);
            return thread;
        });
        FrozenRealAv1RangeServer fixtureServer = new FrozenRealAv1RangeServer(bytes, server, executor);
        server.createContext("/video.m4s", fixtureServer::serve);
        server.setExecutor(executor);
        server.start();
        return fixtureServer;
    }

    URL videoUrl() throws IOException {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/video.m4s").toURL();
    }

    int fullRequests() {
        return fullRequests.get();
    }

    int rangeRequests() {
        return rangeRequests.get();
    }

    boolean servedRangeStartingAt(long expectedStart) {
        return rangeStarts.contains(expectedStart);
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            Headers response = exchange.getResponseHeaders();
            response.set("Accept-Ranges", "bytes");
            response.set("Content-Type", "video/mp4");
            response.set("Cache-Control", "no-store");
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.sendResponseHeaders(405, -1L);
                return;
            }
            String rawRange = exchange.getRequestHeaders().getFirst("Range");
            if (rawRange == null || rawRange.isBlank()) {
                fullRequests.incrementAndGet();
                write(exchange, 200, 0, bytes.length - 1, "HEAD".equals(method));
                return;
            }
            Matcher matcher = RANGE.matcher(rawRange.trim());
            if (!matcher.matches()) {
                response.set("Content-Range", "bytes */" + bytes.length);
                exchange.sendResponseHeaders(416, -1L);
                return;
            }
            long requestedStart;
            long requestedEnd;
            try {
                requestedStart = Long.parseLong(matcher.group(1));
                requestedEnd = matcher.group(2).isEmpty()
                        ? bytes.length - 1L
                        : Long.parseLong(matcher.group(2));
            } catch (NumberFormatException error) {
                response.set("Content-Range", "bytes */" + bytes.length);
                exchange.sendResponseHeaders(416, -1L);
                return;
            }
            if (requestedStart < 0L || requestedStart >= bytes.length || requestedEnd < requestedStart) {
                response.set("Content-Range", "bytes */" + bytes.length);
                exchange.sendResponseHeaders(416, -1L);
                return;
            }
            int start = Math.toIntExact(requestedStart);
            int end = (int) Math.min(bytes.length - 1L, requestedEnd);
            rangeRequests.incrementAndGet();
            rangeStarts.add(requestedStart);
            response.set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
            write(exchange, 206, start, end, "HEAD".equals(method));
        }
    }

    private void write(HttpExchange exchange, int status, int start, int end, boolean head) throws IOException {
        int length = end - start + 1;
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(length));
        exchange.sendResponseHeaders(status, head ? -1L : length);
        if (!head) {
            exchange.getResponseBody().write(bytes, start, length);
        }
    }

    private static byte[] fixture(String resource) throws IOException {
        try (InputStream stream = FrozenRealAv1RangeServer.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing frozen AV1 fixture " + resource);
            }
            return Base64.getMimeDecoder().decode(stream.readAllBytes());
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
