package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

/**
 * Classifies media failures that should be presented to players as network
 * problems.
 */
public final class MediaNetworkFailureClassifier {
    private static final String[] NETWORK_MESSAGE_MARKERS = {
            "cdn", "http ", "timed out", "timeout", "connection", "connect failed",
            "response ended early", "stream interrupted", "waiting for temp spool data",
            "unable to resolve host", "network is unreachable", "broken pipe", "reset by peer"
    };

    private MediaNetworkFailureClassifier() {
    }

    public static boolean isNetworkFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketException) {
                return true;
            }
            if (current instanceof InterruptedIOException && !isIntentionalInterruption(current)) {
                return true;
            }
            String message = current.getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            for (String marker : NETWORK_MESSAGE_MARKERS) {
                if (normalized.contains(marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isIntentionalInterruption(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("interrupted");
    }
}