package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.blockentity.*;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual packet handlers route to actual BEs (and device config handlers). Platform player/world and audience transport are test boundaries. */
class MediaDeviceControlHandlerProductionTest {
    enum Kind { SPEAKER, VIDEO, LYRIC }
    private static final BlockPos POS = new BlockPos(8, 64, 8);
    private ServerLevel level; private ServerPlayer player; private IPayloadContext context;
    private MockedStatic<IndexedBlockPlaybackSessionManager> transport;
    @BeforeAll static void bootstrap() { PlatformBootstrap.initialize(); }
    @BeforeEach void setup() {
        level = mock(ServerLevel.class); player = mock(ServerPlayer.class); context = mock(IPayloadContext.class);
        when(context.player()).thenReturn(player); when(player.level()).thenReturn(level);
        when(player.position()).thenReturn(Vec3.atCenterOf(POS)); when(player.mayBuild()).thenReturn(true);
        transport = mockStatic(IndexedBlockPlaybackSessionManager.class);
    }
    @AfterEach void teardown() { transport.close(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualVolumeHandlerRequiresMayBuildAndClampsRange(boolean live) {
        BlockEntity entity = live ? live() : turntable(); mount(entity);
        when(player.mayBuild()).thenReturn(false); volume(live, -99);
        assertEquals(1000, volume(entity));
        when(player.mayBuild()).thenReturn(true); volume(live, -99); assertEquals(0, volume(entity));
        volume(live, 3000); assertEquals(1000, volume(entity));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void distanceBeyondEightBlocksRejectsBeforeLookingUpBlockEntity(boolean live) {
        when(player.position()).thenReturn(Vec3.atCenterOf(POS).add(8.001, 0, 0));
        volume(live, 123); verify(level, never()).getBlockEntity(any()); verifyNoMoreInteractions(level);
    }

    @Test void turntableRealRepeatExtractionAndRedstoneActionsPersist() {
        ModernTurntableBlockEntity turntable = turntable(); mount(turntable);
        turntable(ModernTurntableControlPacket.Action.TOGGLE_REPEAT_ONE, 0); assertTrue(turntable.isRepeatOne());
        turntable(ModernTurntableControlPacket.Action.CYCLE_EXTRACTION_MODE, 0); assertEquals(TurntableExtractionMode.ALWAYS, turntable.getExtractionMode());
        turntable(ModernTurntableControlPacket.Action.CYCLE_REDSTONE_MODE, 0); assertEquals(TurntableRedstoneMode.HIGH_SIGNAL, turntable.getRedstoneMode());
        CompoundTag encoded = turntable.saveWithoutMetadata(PlatformBootstrap.registries());
        assertTrue(encoded.getBoolean("RepeatOne").orElseThrow()); assertEquals("always", encoded.getString("ExtractionMode").orElseThrow());
    }

    /** Merged from DeviceConfigPacketProductionHandlerTest; keeps its own actor/world fixture and position. */
    @Nested class DeviceConfig {
        static final BlockPos POS=new BlockPos(4,64,8);ServerPlayer actor;ServerLevel level;IPayloadContext context;UUID playerId;
        @BeforeEach void setup(){actor=mock(ServerPlayer.class);level=mock(ServerLevel.class);context=mock(IPayloadContext.class);playerId=UUID.randomUUID();
            when(context.player()).thenReturn(actor);when(actor.level()).thenReturn(level);when(actor.getUUID()).thenReturn(playerId);when(actor.position()).thenReturn(POS.getCenter());}
        @AfterEach void cleanup(){NetworkRateLimiter.removePlayer(playerId);}
        @ParameterizedTest @EnumSource(Kind.class) void outsideEightBlocksIsRejectedBeforeTargetLookup(Kind kind){
            when(actor.position()).thenReturn(POS.getCenter().add(8.001,0,0));handle(kind);verify(level,never()).getBlockEntity(any());}
        @Test void videoAndLyricRateLimitsAreIndependentAndDropNinthRequestBeforeLookup(){
            var video=mock(VideoProjectorBlockEntity.class);var lyric=mock(LyricProjectorBlockEntity.class);
            when(level.getBlockEntity(POS)).thenReturn(video);for(int i=0;i<9;i++)handle(Kind.VIDEO);verify(video,times(8)).markDirtyAndSync();
            clearInvocations(level);when(level.getBlockEntity(POS)).thenReturn(lyric);for(int i=0;i<9;i++)handle(Kind.LYRIC);
            verify(lyric,times(8)).markDirtyAndSync();verify(level,times(8)).getBlockEntity(POS);
        }
        private void handle(Kind k){switch(k){
            case SPEAKER->SpeakerConfigPacket.handle(new SpeakerConfigPacket(POS,3,0.4F,true),context);
            case VIDEO->VideoProjectorConfigPacket.handle(new VideoProjectorConfigPacket(POS,20,10,2,3,4,5,0.7F,80),context);
            case LYRIC->LyricProjectorConfigPacket.handle(new LyricProjectorConfigPacket(POS,20,10,2,3,4,5,1,true),context);
        }}
    }

    private void mount(BlockEntity entity) { entity.setLevel(level); when(level.getBlockEntity(POS)).thenReturn(entity); when(level.getBlockState(POS)).thenReturn(entity.getBlockState()); }
    private ModernTurntableBlockEntity turntable() { return PlatformBootstrap.turntable(POS); }
    private LiveStreamerBlockEntity live() { return PlatformBootstrap.liveStreamer(POS); }
    private int volume(BlockEntity entity) { return entity instanceof ModernTurntableBlockEntity turntable ? turntable.getVolumePerMille() : ((LiveStreamerBlockEntity) entity).getVolumePerMille(); }
    private void volume(boolean live, long value) {
        if (live) LiveStreamerControlPacket.handle(new LiveStreamerControlPacket(POS, LiveStreamerControlPacket.Action.SET_VOLUME, "", (int) value), context);
        else turntable(ModernTurntableControlPacket.Action.SET_VOLUME, value);
    }
    private void turntable(ModernTurntableControlPacket.Action action, long value) { ModernTurntableControlPacket.handle(new ModernTurntableControlPacket(POS, action, value), context); }
}
