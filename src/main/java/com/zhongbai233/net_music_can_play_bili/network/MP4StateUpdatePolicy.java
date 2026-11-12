package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;

/** UI snapshots configure presentation; explicit control commands own playback and selection. */
final class MP4StateUpdatePolicy {
    private MP4StateUpdatePolicy() { }

    /** Copies authoritative playback values; callers validate untrusted packet fields before this boundary. */
    static MP4Item.State withPlayback(MP4Item.State state, boolean playing, int selectedIndex,
            int volumePerMille, int progressPerMille) {
        return new MP4Item.State(playing, state.shuffle(), state.videoEnabled(), state.landscape(),
                state.qualityIndex(), selectedIndex, state.queueScrollOffset(), volumePerMille,
                state.repeatMode(), state.playlistOpen(), state.lyricsEnabled(), state.subtitleMode(),
                state.subtitleAiEnabled(), progressPerMille, state.rotationHintShown());
    }

    static MP4Item.State withSelectedIndex(MP4Item.State state, int selectedIndex) {
        int progress = selectedIndex == state.selectedQueueIndex() ? state.progressPerMille() : 0;
        return withPlayback(state, state.playing(), selectedIndex, state.volumePerMille(), progress);
    }

    static MP4Item.State seekPaused(MP4Item.State current, int selectedIndex, long elapsedMillis, int duration) {
        if (current.playing() || selectedIndex != current.selectedQueueIndex()) {
            return null;
        }
        int progress = MP4PlaybackProgressPolicy.progressPerMille(
                MP4PlaybackProgressPolicy.clampTarget(duration, elapsedMillis), duration);
        return withPlayback(current, false, selectedIndex, current.volumePerMille(), progress);
    }

    static MP4Item.State mergeConfiguration(MP4Item.State current, MP4Item.State incoming) {
        return new MP4Item.State(current.playing(), incoming.shuffle(), incoming.videoEnabled(),
                incoming.landscape(), incoming.qualityIndex(), current.selectedQueueIndex(),
                incoming.queueScrollOffset(), incoming.volumePerMille(), incoming.repeatMode(),
                incoming.playlistOpen(), incoming.lyricsEnabled(), incoming.subtitleMode(),
                incoming.subtitleAiEnabled(), current.progressPerMille(), incoming.rotationHintShown());
    }
}
