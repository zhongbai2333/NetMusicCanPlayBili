package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.net_music_can_play_bili.bili.BiliVideoStreamResolver;

import java.util.List;

record FallbackMedia(String sessionId, long durationMillis, int width, int height, int fps,
            List<BiliVideoStreamResolver.VideoCandidate> candidates) {
    }
