package com.zhongbai233.net_music_can_play_bili.media.audio;

/** Physical output status; cancellation and failure are never natural completion. */
public enum AudioDrainStatus { RUNNING, DRAINING, DRAINED, FAILED, CANCELLED }
