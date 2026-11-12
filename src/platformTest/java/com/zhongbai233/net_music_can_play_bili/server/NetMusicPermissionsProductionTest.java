package com.zhongbai233.net_music_can_play_bili.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.zhongbai233.net_music_can_play_bili.testsupport.PlatformBootstrap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.handler.DefaultPermissionHandler;
import net.neoforged.neoforge.server.permission.handler.IPermissionHandler;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/** Calls the actual permission entry points and NeoForge PermissionAPI; only platform actors are mocks. */
class NetMusicPermissionsProductionTest {
    private static final UUID PLAYER_ID = new UUID(1L, 2L);
    private Set<PermissionNode<?>> nodes;
    private Field activeHandler;
    private Object previousHandler;
    private ServerPlayer player;
    private MinecraftServer server;
    private CommandSourceStack source;

    @BeforeEach void setup() throws Exception {
        PlatformBootstrap.initialize();
        nodes = Set.of(NetMusicPermissions.AUDIT_SOURCES, NetMusicPermissions.PAD_REFRESH,
                NetMusicPermissions.WHITELIST_MANAGE, NetMusicPermissions.CONTROL_CONSOLE_ADMIN);
        activeHandler = PermissionAPI.class.getDeclaredField("activeHandler");
        activeHandler.setAccessible(true); previousHandler = activeHandler.get(null);
        player = mock(ServerPlayer.class); server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class); source = mock(CommandSourceStack.class);
        when(player.getUUID()).thenReturn(PLAYER_ID);
        when(player.getGameProfile()).thenReturn(new GameProfile(PLAYER_ID, "PermissionTest"));
        when(player.level()).thenReturn(level); when(level.getServer()).thenReturn(server);
        when(source.getPlayer()).thenReturn(player); when(source.getServer()).thenReturn(server);
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.ALL);
        install(new DefaultPermissionHandler(nodes));
    }
    @AfterEach void restoreHandler() throws Exception {
        if (activeHandler != null) activeHandler.set(null, previousHandler);
    }

    @Test void gatherRegistersExactlyTheProductionNodes() {
        var event = new PermissionGatherEvent.Nodes(); NetMusicPermissions.onPermissionGather(event);
        assertEquals(nodes, Set.copyOf(event.getNodes()));
        assertEquals("net_music_can_play_bili.whitelist.manage", NetMusicPermissions.WHITELIST_MANAGE.getNodeName());
    }

    @ParameterizedTest @CsvSource({"ALL,false,false", "MODERATORS,false,false", "GAMEMASTERS,true,false",
            "ADMINS,true,false", "OWNERS,true,true"})
    void actualDefaultHandlerEnforcesOpTwoAndOpFourSeparately(PermissionLevel rank, boolean opTwo, boolean opFour) {
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.forLevel(rank));
        assertEquals(opTwo, NetMusicPermissions.has(source, NetMusicPermissions.AUDIT_SOURCES));
        assertEquals(opTwo, NetMusicPermissions.has(source, NetMusicPermissions.PAD_REFRESH));
        assertEquals(opFour, NetMusicPermissions.has(source, NetMusicPermissions.WHITELIST_MANAGE));
        assertEquals(opTwo, NetMusicPermissions.canAdministerControlConsole(source));
    }

    @ParameterizedTest @CsvSource({"ALL,false", "MODERATORS,false", "GAMEMASTERS,true", "ADMINS,true", "OWNERS,true"})
    void consoleVanillaOpFallbackCannotBeVetoedByExternalHandler(PermissionLevel rank, boolean expected) throws Exception {
        var handler = new CountingHandler(false); install(handler);
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.forLevel(rank));
        assertEquals(expected, NetMusicPermissions.canAdministerControlConsole(source));
        assertEquals(expected ? 0 : 1, handler.queries, "OP fallback must short-circuit external veto");
    }

    @ParameterizedTest @CsvSource({"ALL,false,false", "MODERATORS,false,false", "GAMEMASTERS,true,false",
            "ADMINS,true,false", "OWNERS,true,true"})
    void registeredBrigadierRootsUseRealPermissionEntryPoints(PermissionLevel rank, boolean opTwo, boolean opFour) {
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.forLevel(rank));
        var dispatcher = commandDispatcher();
        for (String rootName : new String[] {"netmusicbiliserver", "ncpbs"}) {
            var root = dispatcher.getRoot().getChild(rootName); assertNotNull(root);
            assertEquals(opTwo, root.getChild("sources").canUse(source));
            assertEquals(opTwo, root.getChild("pad").canUse(source));
            assertEquals(opFour, root.getChild("whitelist").canUse(source));
        }
    }

    @Test void deniedWhitelistMutationCannotExecuteThroughActualDispatcher() {
        var dispatcher = commandDispatcher();
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("ncpbs whitelist add BV1xx411c7mD", source));
        verify(source, never()).sendSuccess(any(), anyBoolean());
        verify(source, never()).sendFailure(any());
    }

    private CommandDispatcher<CommandSourceStack> commandDispatcher() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        NetMusicBiliServerCommands.onRegisterCommands(new RegisterCommandsEvent(dispatcher,
                Commands.CommandSelection.ALL, mock(CommandBuildContext.class)));
        return dispatcher;
    }

    @Test void externalHandlerGrantsConsoleAdministratorToNonOp() throws Exception {
        var handler = new CountingHandler(true); install(handler);
        assertTrue(NetMusicPermissions.canAdministerControlConsole(source)); assertEquals(1, handler.queries);
    }

    @Test void genericNodeRespectsExternalHandlerDenyEvenForOpFour() throws Exception {
        var handler = new CountingHandler(false); install(handler);
        when(server.getProfilePermissions(any(NameAndId.class))).thenReturn(LevelBasedPermissionSet.OWNER);
        assertFalse(NetMusicPermissions.has(source, NetMusicPermissions.WHITELIST_MANAGE));
        assertEquals(1, handler.queries);
    }

    @Test void singleplayerOwnerShortCircuitsExternalHandler() throws Exception {
        var handler = new CountingHandler(false); install(handler);
        when(server.isSingleplayerOwner(any(NameAndId.class))).thenReturn(true);
        assertTrue(NetMusicPermissions.has(source, NetMusicPermissions.WHITELIST_MANAGE));
        assertTrue(NetMusicPermissions.canAdministerControlConsole(source)); assertEquals(0, handler.queries);
    }

    @Test void nonPlayerConsoleSourceIsAllowedWithoutHandlerQuery() throws Exception {
        var handler = new CountingHandler(false); install(handler); when(source.getPlayer()).thenReturn(null);
        assertTrue(NetMusicPermissions.has(source, NetMusicPermissions.AUDIT_SOURCES));
        assertTrue(NetMusicPermissions.canAdministerControlConsole(source)); assertEquals(0, handler.queries);
    }

    @Test void missingHandlerAndUnregisteredNodeFailClosed() throws Exception {
        install(null);
        assertFalse(NetMusicPermissions.has(source, NetMusicPermissions.AUDIT_SOURCES));
        assertFalse(NetMusicPermissions.canAdministerControlConsole(source));
        install(new DefaultPermissionHandler(Set.of()));
        assertFalse(NetMusicPermissions.has(source, NetMusicPermissions.AUDIT_SOURCES));
        assertFalse(NetMusicPermissions.canAdministerControlConsole(source));
    }

    @Test void exceptionsFromActorAndExternalHandlerFailClosed() throws Exception {
        var handler = new CountingHandler(true) {
            @Override public <T> T getPermission(ServerPlayer p, PermissionNode<T> node,
                    PermissionDynamicContext<?>... context) { throw new IllegalStateException("unavailable provider"); }
        };
        install(handler);
        assertFalse(NetMusicPermissions.has(source, NetMusicPermissions.WHITELIST_MANAGE));
        assertFalse(NetMusicPermissions.canAdministerControlConsole(source));
        when(source.getPlayer()).thenThrow(new IllegalStateException("disconnected actor"));
        assertFalse(NetMusicPermissions.has(source, NetMusicPermissions.PAD_REFRESH));
        assertFalse(NetMusicPermissions.canAdministerControlConsole(source));
    }

    @Test void nullCommandSourceFailsClosed() {
        assertFalse(NetMusicPermissions.has(null, NetMusicPermissions.PAD_REFRESH));
        assertFalse(NetMusicPermissions.canAdministerControlConsole(null));
    }

    private void install(IPermissionHandler handler) throws IllegalAccessException { activeHandler.set(null, handler); }
    private class CountingHandler implements IPermissionHandler {
        private final boolean allowed; private int queries;
        CountingHandler(boolean allowed) { this.allowed = allowed; }
        public Identifier getIdentifier() { return Identifier.fromNamespaceAndPath("ncpb_test", "permissions"); }
        public Set<PermissionNode<?>> getRegisteredNodes() { return nodes; }
        @SuppressWarnings("unchecked")
        public <T> T getPermission(ServerPlayer player, PermissionNode<T> node,
                PermissionDynamicContext<?>... context) { queries++; return (T) Boolean.valueOf(allowed); }
        public <T> T getOfflinePermission(UUID player, PermissionNode<T> node,
                PermissionDynamicContext<?>... context) { throw new AssertionError("online entry must not use offline API"); }
    }
}
