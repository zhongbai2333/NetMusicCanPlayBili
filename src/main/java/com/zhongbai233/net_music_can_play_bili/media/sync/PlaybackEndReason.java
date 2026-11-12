package com.zhongbai233.net_music_can_play_bili.media.sync;

/** Natural completion alone advances a queue. Pause and cancellation never do. */
public enum PlaybackEndReason { NATURAL_EOF, FAILED, PAUSED, CANCELLED }
