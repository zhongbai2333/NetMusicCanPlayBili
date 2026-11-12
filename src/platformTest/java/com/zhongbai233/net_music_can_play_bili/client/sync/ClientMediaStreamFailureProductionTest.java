package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.github.tartaricacid.netmusic.init.InitSounds;
import com.zhongbai233.net_music_can_play_bili.client.ClientMediaMovingSound;
import com.zhongbai233.net_music_can_play_bili.client.audio.SyncedStreamRecoveryRegistry;
import com.zhongbai233.net_music_can_play_bili.media.sync.MediaRequestToken;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.GameData;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real moving sounds, transport ownership and retry admission; no HTTP, SoundEngine or native output. */
class ClientMediaStreamFailureProductionTest {
    private static final UUID SOURCE = new UUID(104, 1), OWNER = new UUID(104, 2);
    private static final String SESSION = "retained-session", URL = "https://fixture.invalid/audio";
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private MockedStatic<Minecraft> singleton;
    private MockedStatic<ClientMediaAudioRouting> routing;
    private MockedStatic<CompletableFuture> timers;
    private ClientMediaSoundLifecyclePolicy lifecycle;
    private int retries;

    @BeforeAll static void bootstrap() {
        PlatformBootstrap.initialize();
        if (!BuiltInRegistries.SOUND_EVENT.containsKey(InitSounds.NET_MUSIC.getId())) {
            GameData.unfreezeData();
            Registry.register(BuiltInRegistries.SOUND_EVENT, InitSounds.NET_MUSIC.getId(),
                    SoundEvent.createVariableRangeEvent(InitSounds.NET_MUSIC.getId()));
            GameData.freezeData();
        }
    }

    @BeforeEach void setup() {
        clear();
        Minecraft client = mock(Minecraft.class);
        client.level = mock(ClientLevel.class);
        client.player = mock(LocalPlayer.class);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; })
                .when(client).execute(any(Runnable.class));
        singleton = mockStatic(Minecraft.class);
        singleton.when(Minecraft::getInstance).thenReturn(client);
        routing = mockStatic(ClientMediaAudioRouting.class);
        routing.when(() -> ClientMediaAudioRouting.audiblePosition(SOURCE, false)).thenReturn(Vec3.ZERO);
        routing.when(() -> ClientMediaAudioRouting.canHear(SOURCE, false)).thenReturn(true);
        timers = mockStatic(CompletableFuture.class, CALLS_REAL_METHODS);
        timers.when(() -> CompletableFuture.delayedExecutor(0L, TimeUnit.MILLISECONDS))
                .thenReturn((Executor) Runnable::run);
        lifecycle = mock(ClientMediaSoundLifecyclePolicy.class);
        when(lifecycle.tryRegisterSound(eq(SOURCE), eq(SESSION), any()))
                .thenAnswer(call -> ClientMediaSoundRegistry.tryRegister(SOURCE, SESSION, call.getArgument(2)));
        when(lifecycle.recoverAfterStreamFailure(eq(SOURCE), eq(SESSION), any()))
                .thenAnswer(call -> ClientMediaRetryHandler.retryAfterStreamFailure(SOURCE, SESSION,
                        call.getArgument(2), new ClientMediaRetryPolicy() {
                            @Override public long retryDelayMillis() { return 0; }
                            @Override public void scheduleRetry(UUID source, String session,
                                    ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error) { retries++; }
                        }));
        doAnswer(call -> { ClientMediaPlaybackRegistry.finishSession(SOURCE, SESSION); return null; })
                .when(lifecycle).finish(SOURCE, SESSION);
    }

    @AfterEach void cleanup() {
        clear();
        timers.close();
        routing.close();
        singleton.close();
    }

    @Test void retiredTransportCannotRetryItsReplacementButCurrentFailureStillRecovers() throws Exception {
        var firstPayload = payload("a");
        ClientMediaPlaybackRegistry.put(SOURCE, ClientMediaPlaybackRegistry.createFromSync(firstPayload));
        ClientMediaTerminalRegistry.observe(firstPayload, SOURCE);
        TestSound old = new TestSound(firstPayload.playUrl());

        var nextPayload = payload("b");
        ClientMediaTerminalRegistry.observe(nextPayload, SOURCE);
        // The new transport is authoritative before its replacement sound has finished preparation.
        old.fail();
        drain();
        TestSound replacement = new TestSound(nextPayload.playUrl());
        old.fail();
        // A retired decoder must not bypass sound ownership through a logical-session-only callback.
        SyncedStreamRecoveryRegistry.reportFailure(SESSION, URI.create(URL).toURL(), new IOException("retired"));
        drain();
        replacement.tick();
        assertAll(() -> assertEquals(0, retries),
                () -> assertFalse(ClientMediaRetryHandler.isPending(SOURCE, SESSION)),
                () -> assertFalse(replacement.stopped()),
                () -> assertSame(replacement, ClientMediaSoundRegistry.get(SOURCE)),
                () -> assertNotNull(ClientMediaPlaybackRegistry.get(SOURCE)));

        replacement.fail();
        drain();
        assertEquals(1, retries);
        assertTrue(ClientMediaRetryHandler.isPending(SOURCE, SESSION));
        verify(lifecycle, never()).finish(any(), any());

        ClientMediaRetryHandler.clearPending();
        when(lifecycle.recoverAfterStreamFailure(eq(SOURCE), eq(SESSION), any())).thenReturn(false);
        replacement.fail();
        drain();
        verify(lifecycle).finish(SOURCE, SESSION);
        assertNull(ClientMediaPlaybackRegistry.get(SOURCE));
    }

    private static MP4PlaybackSyncPacket payload(String token) {
        return new MP4PlaybackSyncPacket(OWNER, SOURCE, ClientMediaSyncPayload.SOURCE_PLAYER, 0,
                0, 0, 0, true, 0, PlaybackSync.withTerminalToken(URL, new MediaRequestToken(token)),
                URL, "track", 40, 1000, SESSION, 0, false);
    }

    private void drain() {
        while (!tasks.isEmpty()) tasks.remove().run();
    }

    private static void clear() {
        ClientMediaRetryHandler.clearPending();
        ClientMediaSoundRegistry.clearAndDiscard();
        ClientMediaPlaybackRegistry.clear();
        ClientMediaTerminalRegistry.clear();
        SyncedStreamRecoveryRegistry.clear();
    }

    private final class TestSound extends ClientMediaMovingSound {
        TestSound(String playUrl) throws Exception {
            super(SOURCE, URI.create(URL).toURL(), 40, null, SESSION, 0, 1F, false, lifecycle, "Retry regression");
            withTerminalFeedback(playUrl);
        }

        void fail() { super.onStreamFailure(new IOException("controlled transport failure")); }
    }
}
