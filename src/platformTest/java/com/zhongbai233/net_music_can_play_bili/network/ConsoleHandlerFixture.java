package com.zhongbai233.net_music_can_play_bili.network;

import com.mojang.authlib.GameProfile;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleConsumerLeaseRegistry;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsoleEditLeaseRegistry;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real console document, lease registries and handlers. Mocks only platform actors/world lookup and network delivery. */
abstract class ConsoleHandlerFixture {
    static final UUID OWNER = new UUID(71L, 1L), OTHER = new UUID(71L, 2L);
    static final BlockPos POS = new BlockPos(-17, 65, -33), SOURCE_POS = new BlockPos(32, 65, 0);
    ServerPlayer actor;
    ServerLevel level;
    MinecraftServer server;
    IPayloadContext context;
    ControlConsoleBlockEntity console;
    MockedStatic<PacketDistributor> packets;
    final List<CustomPacketPayload> sent = new ArrayList<>();
    final List<String> effects = new ArrayList<>();

    @BeforeAll static void initializePlatform() { PlatformBootstrap.initialize(); }
    @BeforeEach void fixture() {
        ControlConsoleEditLeaseRegistry.clear(); ControlConsoleConsumerLeaseRegistry.clear();
        NetworkRateLimiter.removePlayer(OWNER); NetworkRateLimiter.removePlayer(OTHER);
        actor = mock(ServerPlayer.class); level = mock(ServerLevel.class); server = mock(MinecraftServer.class);
        context = mock(IPayloadContext.class); var command = mock(CommandSourceStack.class);
        when(context.player()).thenReturn(actor); when(actor.getUUID()).thenReturn(OWNER);
        when(actor.getGameProfile()).thenReturn(new GameProfile(OWNER, "ConsoleOwner"));
        when(actor.level()).thenReturn(level); when(actor.mayBuild()).thenReturn(true);
        when(actor.createCommandSourceStack()).thenReturn(command);
        when(command.getPlayer()).thenReturn(actor); when(command.getServer()).thenReturn(server);
        when(level.getServer()).thenReturn(server); when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.ALL);
        when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
        console = spy(PlatformBootstrap.console(POS)); console.setLevel(level);
        when(level.getBlockEntity(POS)).thenReturn(console);
        doAnswer(invocation -> { effects.add("dirty:" + console.documentRevision()); return null; })
                .when(level).blockEntityChanged(POS);
        doAnswer(invocation -> { effects.add("block-update:" + console.documentRevision()); return null; })
                .when(level).sendBlockUpdated(eq(POS), any(), any(), eq(3));
        packets = mockStatic(PacketDistributor.class);
        packets.when(() -> PacketDistributor.sendToPlayer(any(ServerPlayer.class), any(CustomPacketPayload.class)))
                .thenAnswer(invocation -> {
                    CustomPacketPayload payload = invocation.getArgument(1); sent.add(payload);
                    effects.add("ack:" + console.documentRevision()); return null;
                });
        console.claimIfUnowned(OWNER); position(0D, 0D, 0D); clearEffects();
    }
    @AfterEach void cleanupFixture() {
        packets.close(); ControlConsoleEditLeaseRegistry.clear(); ControlConsoleConsumerLeaseRegistry.clear();
        NetworkRateLimiter.removePlayer(OWNER); NetworkRateLimiter.removePlayer(OTHER);
    }
    void clearEffects() { sent.clear(); effects.clear(); clearInvocations(console, level); }
    void position(double x, double y, double z) {
        Vec3 point = Vec3.atCenterOf(POS).add(x, y, z);
        when(actor.position()).thenReturn(point); when(actor.getX()).thenReturn(point.x);
        when(actor.getY()).thenReturn(point.y); when(actor.getZ()).thenReturn(point.z);
    }
    UUID editingLease() {
        return ControlConsoleEditLeaseRegistry.acquire(ControlConsoleEditLeasePacket.key(level, POS),
                actor.getUUID(), System.currentTimeMillis()).leaseId();
    }
    ControlConsoleConfigPacket config(UUID lease, UUID operation, long revision, String name) {
        return new ControlConsoleConfigPacket(POS, lease, operation, revision, name, 64D, 32D, 64D, List.of());
    }
    void bindSource(ControlConsoleDocument.SourceKind kind) {
        console.linkTo("minecraft:overworld", SOURCE_POS, kind);
        when(level.getBlockEntity(SOURCE_POS)).thenReturn(kind == ControlConsoleDocument.SourceKind.TURNTABLE
                ? PlatformBootstrap.turntable(SOURCE_POS) : PlatformBootstrap.liveStreamer(SOURCE_POS));
        clearEffects();
    }
    <T> T onlyAck(Class<T> type) { assertEquals(1, sent.size()); return type.cast(sent.getFirst()); }
    void assertNoDocumentMutation(ControlConsoleDocument before) {
        assertSame(before, console.document());
        assertTrue(effects.stream().noneMatch(effect -> effect.startsWith("dirty:") || effect.startsWith("block-update:")));
        verify(console, never()).replaceDocument(any(UUID.class), anyLong(), anyString(), anyDouble(), anyDouble(), anyDouble(), anyList());
    }
}
