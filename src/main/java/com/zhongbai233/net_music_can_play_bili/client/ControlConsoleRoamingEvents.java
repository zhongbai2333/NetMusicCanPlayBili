package com.zhongbai233.net_music_can_play_bili.client;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** 灵魂漫游的 NeoForge 事件桥接。 */
public final class ControlConsoleRoamingEvents {
    private ControlConsoleRoamingEvents() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        ControlConsoleRoamingSession.tick();
    }

    public static void onMovementInput(MovementInputUpdateEvent event) {
        ControlConsoleRoamingSession.suppressPlayerInput(event.getInput());
    }

    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (ControlConsoleRoamingSession.handleMouseButton(event.getButton(), event.getAction())) {
            event.setCanceled(true);
        }
    }

    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (ControlConsoleRoamingSession.isActive()
                && (event.isAttack() || event.isUseItem() || event.isPickBlock())) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (ControlConsoleRoamingSession.isActive()) {
            ControlConsoleRoamingSession.adjustFlyingSpeed(event.getScrollDeltaY());
            event.setCanceled(true);
        }
    }

    public static void onRenderGui(RenderGuiEvent.Post event) {
        ControlConsoleRoamingSession.drawHud(event.getGuiGraphics());
    }

    public static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
        ControlConsoleRoamingSession.submitGeometry(event.getSubmitNodeCollector());
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ControlConsoleRoamingSession.stop(false);
    }

    public static void onClone(ClientPlayerNetworkEvent.Clone event) {
        ControlConsoleRoamingSession.stop(false);
    }
}