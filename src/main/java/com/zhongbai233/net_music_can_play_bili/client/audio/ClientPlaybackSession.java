package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次客户端播放的显式生命周期和 cancellation token。
 *
 * <p>
 * 资源可以在 session 被取消前或取消后绑定；取消动作保证至多执行一次。
 * </p>
 */
public final class ClientPlaybackSession {
    public enum State {
        PREPARING,
        BUFFERING,
        PLAYING,
        RECOVERING,
        STOPPING,
        STOPPED,
        FAILED
    }

    private final PlaybackSessionId sessionId;
    private final long expiresAtMillis;
    private final long suppressUntilMillis;
    private final List<Runnable> cancellationActions = new ArrayList<>();
    private final Map<String, Runnable> replaceableResources = new LinkedHashMap<>();
    private State state = State.PREPARING;
    private boolean cancelled;

    ClientPlaybackSession(String sessionId, long expiresAtMillis, long suppressUntilMillis) {
        this(PlaybackSessionId.of(sessionId), expiresAtMillis, suppressUntilMillis);
    }

    ClientPlaybackSession(PlaybackSessionId sessionId, long expiresAtMillis, long suppressUntilMillis) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.expiresAtMillis = expiresAtMillis;
        this.suppressUntilMillis = suppressUntilMillis;
    }

    public String sessionId() {
        return sessionId.value();
    }

    public PlaybackSessionId playbackSessionId() {
        return sessionId;
    }

    public long expiresAtMillis() {
        return expiresAtMillis;
    }

    public long suppressUntilMillis() {
        return suppressUntilMillis;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized boolean isTerminal() {
        return state == State.STOPPED || state == State.FAILED;
    }

    public synchronized boolean transitionTo(State next) {
        if (next == null || state == next || isTerminal() || cancelled && next != State.STOPPED) {
            return false;
        }
        if (!canTransition(state, next)) {
            return false;
        }
        state = next;
        return true;
    }

    public void onCancel(Runnable action) {
        if (action == null) {
            return;
        }
        boolean runNow;
        synchronized (this) {
            runNow = cancelled;
            if (!runNow) {
                cancellationActions.add(action);
            }
        }
        if (runNow) {
            runSafely(action);
        }
    }

    /**
     * 原子替换同一职责槽位的资源。旧资源在替换后立即取消；session 已结束时，新资源立即取消。
     *
     * @return 资源是否成功绑定到仍存活的 session
     */
    public boolean replaceResource(String slot, Runnable cancellationAction) {
        if (slot == null || slot.isBlank() || cancellationAction == null) {
            return false;
        }
        Runnable replaced = null;
        boolean bound;
        synchronized (this) {
            bound = !cancelled && !isTerminal();
            if (bound) {
                replaced = replaceableResources.put(slot, cancellationAction);
            }
        }
        if (replaced != null) {
            runSafely(replaced);
        }
        if (!bound) {
            runSafely(cancellationAction);
        }
        return bound;
    }

    public boolean cancel() {
        List<Runnable> actions;
        synchronized (this) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            if (!isTerminal()) {
                state = State.STOPPING;
            }
            actions = new ArrayList<>(cancellationActions.size() + replaceableResources.size());
            actions.addAll(cancellationActions);
            actions.addAll(replaceableResources.values());
            cancellationActions.clear();
            replaceableResources.clear();
        }
        actions.forEach(ClientPlaybackSession::runSafely);
        synchronized (this) {
            if (state == State.STOPPING) {
                state = State.STOPPED;
            }
        }
        return true;
    }

    public synchronized boolean fail() {
        if (cancelled || isTerminal()) {
            return false;
        }
        state = State.FAILED;
        return true;
    }

    private static boolean canTransition(State current, State next) {
        return switch (current) {
            case PREPARING -> next == State.BUFFERING || next == State.PLAYING || next == State.FAILED;
            case BUFFERING -> next == State.PLAYING || next == State.RECOVERING || next == State.FAILED;
            case PLAYING -> next == State.RECOVERING || next == State.FAILED;
            case RECOVERING -> next == State.BUFFERING || next == State.PLAYING || next == State.FAILED;
            case STOPPING -> next == State.STOPPED;
            case STOPPED, FAILED -> false;
        };
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 取消必须继续执行其他资源的清理动作。
        }
    }
}
