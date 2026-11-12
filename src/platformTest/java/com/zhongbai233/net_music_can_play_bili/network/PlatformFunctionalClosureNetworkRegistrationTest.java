package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Calls actual registration. FML event and registrar are replaced;
 * this proves the application's registration seam, never loader transformation or Netty transport. */
class PlatformFunctionalClosureNetworkRegistrationTest {
    private record Expected(String direction, Class<? extends CustomPacketPayload> packetClass, String wireId) {}
    // Explicit protocol-7 contract; additions or changes require updating the compatibility review.
    private static final List<Expected> EXPECTED = List.of(
            new Expected("playToServer", MP4PlaybackEndPacket.class, "ncpb:mp4_playback_end"),
            new Expected("playToServer", ModernTurntableControlPacket.class, "ncpb:modern_turntable_control"),
            new Expected("playToClient", ModernTurntableStopPacket.class, "ncpb:modern_turntable_stop"),
            new Expected("playToClient", AudioEndpointSnapshotPacket.class, "ncpb:audio_endpoint_snapshot"),
            new Expected("playToClient", AreaAudioListenerPacket.class, "ncpb:area_audio_listener"),
            new Expected("playToServer", LiveStreamerControlPacket.class, "ncpb:live_streamer_control"),
            new Expected("playToServer", LyricProjectorConfigPacket.class, "ncpb:lyric_projector_config"),
            new Expected("playToServer", SpeakerConfigPacket.class, "ncpb:speaker_config"),
            new Expected("playToServer", VideoProjectorConfigPacket.class, "ncpb:video_projector_config"),
            new Expected("playToServer", HolographicGlassesConfigPacket.class, "ncpb:holographic_glasses_config"),
            new Expected("playToServer", ControlConsoleConfigPacket.class, "ncpb:control_console_config"),
            new Expected("playToServer", ControlConsoleAccessPacket.class, "ncpb:control_console_access"),
            new Expected("playToServer", ControlConsoleEditLeasePacket.class, "ncpb:control_console_edit_lease"),
            new Expected("playToClient", ControlConsoleEditLeaseResultPacket.class, "ncpb:control_console_edit_lease_result"),
            new Expected("playToServer", ControlConsoleConsumerLeasePacket.class, "ncpb:control_console_consumer_lease"),
            new Expected("playToClient", ControlConsoleConsumerLeaseResultPacket.class, "ncpb:control_console_consumer_lease_result"),
            new Expected("playToClient", ControlConsoleConfigResultPacket.class, "ncpb:control_console_config_result"),
            new Expected("playToServer", ClearEquippedBindingPacket.class, "ncpb:clear_equipped_binding"),
            new Expected("playToServer", MediaToolConfirmBindingPacket.class, "ncpb:media_tool_confirm_binding"),
            new Expected("playToServer", MediaToolClearBindingPacket.class, "ncpb:media_tool_clear_binding"),
            new Expected("playToServer", MediaToolReportPacket.class, "ncpb:media_tool_report"),
            new Expected("playToServer", WhitelistReviewActionPacket.class, "ncpb:whitelist_review_action"),
            new Expected("playToServer", MP4StatePacket.class, "ncpb:mp4_state"),
            new Expected("playToServer", MP4PlaybackControlPacket.class, "ncpb:mp4_playback_control"),
            new Expected("playToServer", MP4PlaybackRetryPacket.class, "ncpb:mp4_playback_retry"),
            new Expected("playToServer", MP4EnsureDeviceIdPacket.class, "ncpb:mp4_ensure_device_id"),
            new Expected("playToServer", MP4EnsureInventoryDeviceIdPacket.class, "ncpb:mp4_ensure_inventory_device_id"),
            new Expected("playToServer", PadStatePacket.class, "ncpb:pad_state"),
            new Expected("playToServer", PadPublishPacket.class, "ncpb:pad_publish"),
            new Expected("playToServer", PadPlaybackControlPacket.class, "ncpb:pad_playback_control"),
            new Expected("playToServer", PadPlaybackRetryPacket.class, "ncpb:pad_playback_retry"),
            new Expected("playToClient", MP4DeviceIdPacket.class, "ncpb:mp4_device_id"),
            new Expected("playToClient", MP4InventoryDeviceIdPacket.class, "ncpb:mp4_inventory_device_id"),
            new Expected("playToClient", MP4ContainerDeviceIdPacket.class, "ncpb:mp4_container_device_id"),
            new Expected("playToClient", MP4DeviceStateMirrorPacket.class, "ncpb:mp4_device_state_mirror"),
            new Expected("playToClient", MP4PlaybackSyncPacket.class, "ncpb:mp4_playback_sync"),
            new Expected("playToClient", MP4PlaybackTimelinePacket.class, "ncpb:mp4_playback_timeline"),
            new Expected("playToClient", PadStateMirrorPacket.class, "ncpb:pad_state_mirror"),
            new Expected("playToClient", WhitelistCsvExportPacket.class, "ncpb:whitelist_csv_export"),
            new Expected("playToClient", WhitelistReviewPacket.class, "ncpb:whitelist_review"),
            new Expected("playToClient", WhitelistReviewMutationResultPacket.class, "ncpb:whitelist_review_mutation_result"),
            new Expected("playToClient", WhitelistPreviewPacket.class, "ncpb:whitelist_preview"),
            new Expected("playToClient", PadMapWorldScopePacket.class, "ncpb:pad_map_world_scope"));

    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }

    @Test void productionRegisterUsesProtocolSevenAndOnlyExpectedUnidirectionalPayloads() throws Exception {
        var event = mock(RegisterPayloadHandlersEvent.class);
        var registrar = mock(PayloadRegistrar.class);
        var networkRegistrar = mock(PayloadRegistrar.class);
        when(registrar.executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK)).thenReturn(networkRegistrar);
        when(event.registrar("7")).thenReturn(registrar);
        ModernTurntableNetwork.register(event);
        verify(event).registrar("7");
        verifyNoMoreInteractions(event);
        verify(registrar, times(1)).executesOn(net.neoforged.neoforge.network.registration.HandlerThread.NETWORK);
        var networkCalls = List.copyOf(mockingDetails(networkRegistrar).getInvocations());
        assertEquals(1, networkCalls.size(), "Only MP4 sync receipt may execute directly on the network thread");
        assertSame(MP4PlaybackSyncPacket.TYPE, networkCalls.getFirst().getArgument(0));
        List<Invocation> registrations = Stream.concat(mockingDetails(registrar).getInvocations().stream(),
                networkCalls.stream()).filter(i -> i.getMethod().getName().startsWith("playTo"))
                .toList();
        Map<Object, Invocation> byType = registrations.stream().collect(Collectors.toMap(
                invocation -> invocation.getArgument(0), Function.identity()));
        var expectedTypes = new HashSet<Object>();
        var ids = new HashSet<String>();
        for (Expected expected : EXPECTED) {
            Object type = expected.packetClass().getField("TYPE").get(null);
            assertTrue(expectedTypes.add(type), "duplicate expected packet type");
            Invocation actual = byType.get(type);
            assertNotNull(actual, expected.packetClass().getSimpleName());
            assertEquals(expected.direction(), actual.getMethod().getName(), expected.packetClass().getSimpleName());
            assertEquals(3, actual.getArguments().length);
            assertSame(type, actual.getArgument(0));
            assertSame(expected.packetClass().getField("STREAM_CODEC").get(null), actual.getArgument(1));
            assertNotNull(actual.getArgument(2));
            assertTrue(ids.add(((CustomPacketPayload.Type<?>) type).id().toString()), "duplicate registered identifier");
            assertEquals(expected.wireId(), ((CustomPacketPayload.Type<?>) type).id().toString(),
                    expected.packetClass().getSimpleName() + " exact existing protocol-7 wire identifier");
        }
        assertEquals(expectedTypes, byType.keySet(), "unexpected or missing registered payload");
    }

}
