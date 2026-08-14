package com.zhongbai233.net_music_can_play_bili.media.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bridge.LibraryBridge;
import com.zhongbai233.net_music_can_play_bili.bridge.SoundEngineBridge;
import com.zhongbai233.net_music_can_play_bili.bridge.SoundManagerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.slf4j.Logger;

/** Resolves and activates Minecraft's OpenAL context for auxiliary media workers. */
final class MinecraftOpenAlContext {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<Long> CAPABILITIES_CONTEXT = ThreadLocal.withInitial(() -> 0L);
    private static final Object CACHE_LOCK = new Object();
    private static volatile OpenAlHandles cachedHandles;
    private static volatile boolean warningLogged;

    private MinecraftOpenAlContext() {
    }

    static boolean ensure(String operation) {
        long context = ALC10.alcGetCurrentContext();
        long device = 0L;
        if (context == 0L) {
            OpenAlHandles handles = handles();
            context = handles.context();
            device = handles.device();
            if (context == 0L) {
                LOGGER.debug("OpenAL spatial {} skipped: no current Minecraft OpenAL context", operation);
                return false;
            }
            if (!ALC10.alcMakeContextCurrent(context)) {
                LOGGER.warn("OpenAL spatial {} skipped: failed to make Minecraft OpenAL context current", operation);
                return false;
            }
        }
        if (device == 0L) {
            device = ALC10.alcGetContextsDevice(context);
        }
        if (device == 0L) {
            LOGGER.warn("OpenAL spatial {} skipped: no OpenAL device for context", operation);
            return false;
        }
        if (CAPABILITIES_CONTEXT.get() != context) {
            try {
                AL.createCapabilities(ALC.createCapabilities(device));
                CAPABILITIES_CONTEXT.set(context);
            } catch (IllegalStateException error) {
                invalidate();
                LOGGER.warn("OpenAL spatial {} skipped: context lost between check and capability init", operation);
                return false;
            }
        }
        return true;
    }

    static void invalidate() {
        cachedHandles = null;
    }

    private static OpenAlHandles handles() {
        OpenAlHandles cached = cachedHandles;
        if (cached != null) {
            return cached;
        }
        synchronized (CACHE_LOCK) {
            cached = cachedHandles;
            if (cached == null) {
                cached = resolve();
                cachedHandles = cached;
            }
            return cached;
        }
    }

    private static OpenAlHandles resolve() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return OpenAlHandles.EMPTY;
            }
            SoundManager soundManager = minecraft.getSoundManager();
            if (soundManager == null) {
                return OpenAlHandles.EMPTY;
            }
            SoundEngine soundEngine = ((SoundManagerBridge) soundManager).net_music_can_play_bili$soundEngine();
            if (soundEngine == null) {
                return OpenAlHandles.EMPTY;
            }
            SoundEngineBridge soundEngineBridge = (SoundEngineBridge) soundEngine;
            if (!soundEngineBridge.net_music_can_play_bili$loaded()) {
                return OpenAlHandles.EMPTY;
            }
            var library = soundEngineBridge.net_music_can_play_bili$library();
            if (library == null) {
                return OpenAlHandles.EMPTY;
            }
            LibraryBridge libraryBridge = (LibraryBridge) library;
            long context = libraryBridge.net_music_can_play_bili$context();
            long device = libraryBridge.net_music_can_play_bili$currentDevice();
            if (context == 0L) {
                return OpenAlHandles.EMPTY;
            }
            LOGGER.debug("Cached Minecraft OpenAL handles: context=0x{} device=0x{}",
                    Long.toHexString(context), Long.toHexString(device));
            return new OpenAlHandles(context, device);
        } catch (Throwable error) {
            if (!warningLogged) {
                warningLogged = true;
                LOGGER.warn(
                        "[NetMusicCanPlayBili] Dolby 空间音频不可用：无法获取 Minecraft SoundEngine 的 OpenAL 句柄。"
                                + "这通常是因为当前 Minecraft/NeoForge 版本与模组不兼容（SoundEngine/Library 内部字段名已变更）。"
                                + "音频将自动降级为 FLAC/AAC 立体声。具体异常: {}",
                        error.toString());
            } else {
                LOGGER.debug("OpenAL handle resolution retry failed: {}", error.toString());
            }
            return OpenAlHandles.EMPTY;
        }
    }

    private record OpenAlHandles(long context, long device) {
        private static final OpenAlHandles EMPTY = new OpenAlHandles(0L, 0L);
    }
}
