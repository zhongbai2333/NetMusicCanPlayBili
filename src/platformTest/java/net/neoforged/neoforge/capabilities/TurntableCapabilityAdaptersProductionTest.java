package net.neoforged.neoforge.capabilities;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Same-package access constructs the real event and restores its global provider map after this suite. */
class TurntableCapabilityAdaptersProductionTest {
    private static final BlockPos POS = new BlockPos(8, 9, 10);
    private static Map<?, ?> originalProviders;
    private ServerLevel level; private ModernTurntableBlockEntity turntable;
    private AtomicReference<BlockEntity> current; private CapabilityListenerHolder listeners;
    @BeforeAll static void registerActualProductionProvider() throws Exception {
        PlatformBootstrap.initialize();
        originalProviders = new IdentityHashMap<>(Capabilities.Item.BLOCK.providers);
        Capabilities.Item.BLOCK.providers.clear();
        var event = new RegisterCapabilitiesEvent();
        var method = NetMusicCanPlayBili.class.getDeclaredMethod("registerCapabilities", RegisterCapabilitiesEvent.class);
        method.setAccessible(true);
        // No constructor: prevents unrelated launcher/config/client/event registration side effects.
        method.invoke(mock(NetMusicCanPlayBili.class, CALLS_REAL_METHODS), event);
        assertTrue(event.isBlockRegistered(Capabilities.Item.BLOCK, PlatformBootstrap.turntable(POS).getBlockState().getBlock()));
    }
    @AfterAll @SuppressWarnings({"unchecked", "rawtypes"}) static void restoreProviders() {
        Capabilities.Item.BLOCK.providers.clear(); Capabilities.Item.BLOCK.providers.putAll((Map) originalProviders);
    }
    @BeforeEach void worldBoundary() throws Exception {
        level = mock(ServerLevel.class); turntable = PlatformBootstrap.turntable(POS); turntable.setLevel(level);
        current = new AtomicReference<>(turntable); listeners = new CapabilityListenerHolder();
        when(level.getBlockEntity(POS)).thenAnswer(call -> current.get());
        when(level.getBlockState(POS)).thenAnswer(call -> current.get() == null ? Blocks.AIR.defaultBlockState() : current.get().getBlockState());
        when(level.isLoaded(POS)).thenReturn(true);
        when(level.getCapability(eq(Capabilities.Item.BLOCK), eq(POS), nullable(Direction.class))).thenCallRealMethod();
        var field = ServerLevel.class.getDeclaredField("capListenerHolder"); field.setAccessible(true); field.set(level, listeners);
        doCallRealMethod().when(level).registerCapabilityListener(eq(POS), any());
        doCallRealMethod().when(level).invalidateCapabilities(POS);
    }
    @Test void actualEventProviderServesEverySideAndCommitsActualTransaction() {
        for (Direction side : Direction.values()) assertSame(turntable.getItemHandler(), level.getCapability(Capabilities.Item.BLOCK, POS, side));
        var queried = level.getCapability(Capabilities.Item.BLOCK, POS, null); assertSame(turntable.getItemHandler(), queried);
        var disc = PlatformBootstrap.disc("https://example.test/a.mp3", 20);
        try (var tx = Transaction.openRoot()) { assertEquals(1, queried.insert(0, ItemResource.of(disc), 2, tx)); tx.commit(); }
        assertTrue(ItemResource.of(disc).matches(turntable.getDisc())); assertEquals(1, turntable.getDisc().getCount());
    }
    @Test void actualAdapterRejectsWrongBeTypeAndMissingWorldObject() {
        current.set(PlatformBootstrap.console(POS));
        assertNull(level.getCapability(Capabilities.Item.BLOCK, POS, Direction.DOWN));
        // Same registered turntable state with a wrong BE also fails the real type gate.
        assertNull(Capabilities.Item.BLOCK.getCapability(level, POS, turntable.getBlockState(), current.get(), Direction.DOWN));
        current.set(null); assertNull(level.getCapability(Capabilities.Item.BLOCK, POS, null));
    }
    @Test void removalInvalidatesActualCacheAndReplacementReturnsNewHandler() {
        AtomicInteger notifications = new AtomicInteger();
        var cache = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, POS, Direction.DOWN, () -> true, notifications::incrementAndGet);
        assertSame(turntable.getItemHandler(), cache.getCapability());
        current.set(null); turntable.setRemoved();
        assertEquals(1, notifications.get()); assertNull(cache.getCapability());
        var replacement = PlatformBootstrap.turntable(POS); replacement.setLevel(level); current.set(replacement);
        replacement.clearRemoved(); assertEquals(2, notifications.get());
        assertSame(replacement.getItemHandler(), cache.getCapability()); assertNotSame(turntable.getItemHandler(), cache.getCapability());
    }
    @Test void unloadedPositionReturnsNoHandlerUntilActualInvalidationAndReload() {
        AtomicInteger notifications = new AtomicInteger();
        when(level.isLoaded(POS)).thenReturn(false);
        var cache = BlockCapabilityCache.create(Capabilities.Item.BLOCK, level, POS, Direction.NORTH, () -> true, notifications::incrementAndGet);
        assertNull(cache.getCapability()); when(level.isLoaded(POS)).thenReturn(true);
        assertNull(cache.getCapability()); level.invalidateCapabilities(POS);
        assertEquals(1, notifications.get()); assertSame(turntable.getItemHandler(), cache.getCapability());
    }
    @Test void queriedHandlerRollbackDoesNotLeakAnInsertedDisc() {
        var queried = level.getCapability(Capabilities.Item.BLOCK, POS, Direction.UP);
        try (var tx = Transaction.openRoot()) { assertEquals(1, queried.insert(0, ItemResource.of(PlatformBootstrap.disc("https://example.test/a.mp3", 20)), 1, tx)); }
        assertFalse(turntable.hasDisc()); assertEquals(0, queried.getAmountAsLong(0));
    }
}
