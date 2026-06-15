package com.zhongbai233.net_music_can_play_bili.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModernTurntableNetwork {
    private ModernTurntableNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                ModernTurntableControlPacket.TYPE,
                ModernTurntableControlPacket.STREAM_CODEC,
                ModernTurntableControlPacket::handle);
        registrar.playToServer(
                LyricProjectorConfigPacket.TYPE,
                LyricProjectorConfigPacket.STREAM_CODEC,
                LyricProjectorConfigPacket::handle);
        registrar.playToServer(
                SpeakerConfigPacket.TYPE,
                SpeakerConfigPacket.STREAM_CODEC,
                SpeakerConfigPacket::handle);
        registrar.playToServer(
                VideoProjectorConfigPacket.TYPE,
                VideoProjectorConfigPacket.STREAM_CODEC,
                VideoProjectorConfigPacket::handle);
        registrar.playToServer(
                MP4StatePacket.TYPE,
                MP4StatePacket.STREAM_CODEC,
                MP4StatePacket::handle);
        registrar.playToServer(
                MP4QueueSelectionPacket.TYPE,
                MP4QueueSelectionPacket.STREAM_CODEC,
                MP4QueueSelectionPacket::handle);
        registrar.playToServer(
                MP4PlaybackControlPacket.TYPE,
                MP4PlaybackControlPacket.STREAM_CODEC,
                MP4PlaybackControlPacket::handle);
        registrar.playToServer(
                MP4EnsureDeviceIdPacket.TYPE,
                MP4EnsureDeviceIdPacket.STREAM_CODEC,
                MP4EnsureDeviceIdPacket::handle);
        registrar.playToClient(
                MP4DeviceIdPacket.TYPE,
                MP4DeviceIdPacket.STREAM_CODEC,
                MP4DeviceIdPacket::handle);
        registrar.playToClient(
                MP4OpenStatePacket.TYPE,
                MP4OpenStatePacket.STREAM_CODEC,
                MP4OpenStatePacket::handle);
        registrar.playToClient(
                MP4DeviceStateMirrorPacket.TYPE,
                MP4DeviceStateMirrorPacket.STREAM_CODEC,
                MP4DeviceStateMirrorPacket::handle);
        registrar.playToClient(
                MP4PlaybackSyncPacket.TYPE,
                MP4PlaybackSyncPacket.STREAM_CODEC,
                MP4PlaybackSyncPacket::handle);
        registrar.playToClient(
                MP4PlaybackVolumePacket.TYPE,
                MP4PlaybackVolumePacket.STREAM_CODEC,
                MP4PlaybackVolumePacket::handle);
    }
}
