package com.zhongbai233.net_music_can_play_bili.client;

import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.gui.ControlConsoleGuideScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigPacket;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleConfigResultPacket;

import java.lang.ref.WeakReference;
import java.util.UUID;

/** 控制台客户端编辑入口。具体提交仍由服务端文档协议负责。 */
public final class ControlConsoleClient {
    private static final long RENEW_INTERVAL_MILLIS = 5_000L;
    private static BlockPos pendingOpen;
    private static ActiveLease activeLease;
    private static final long CLOSE_SAVE_TIMEOUT_NANOS = 15_000_000_000L;
    private static ClosingSave closingSave;

    private ControlConsoleClient() {
    }

    public static void openScreen(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            BlockPos immutablePos = pos.immutable();
            if (closingSave != null) {
                tickClosingSave(System.nanoTime());
                if (closingSave != null) {
                    // Finish the closed editor's latest draft before releasing its lease.
                    pendingOpen = immutablePos;
                    return;
                }
            }
            if (activeLease != null && activeLease.pos().equals(immutablePos)) {
                openGrantedWorkflow(immutablePos, minecraft);
                return;
            }
            if (activeLease != null) {
                releaseLease(activeLease.pos());
            }
            pendingOpen = immutablePos;
            ClientPacketDistributor.sendToServer(new com.zhongbai233.net_music_can_play_bili.network
                    .ControlConsoleEditLeasePacket(pos,
                    com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeasePacket.Action.OPEN,
                    null));
        }
    }

    public static void acceptLeaseResult(
            com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeaseResultPacket result) {
        Minecraft minecraft = Minecraft.getInstance();
        if (activeLease != null && activeLease.pos().equals(result.pos())) {
            if (result.status() == com.zhongbai233.net_music_can_play_bili.network
                    .ControlConsoleEditLeaseResultPacket.Status.GRANTED
                    && activeLease.leaseId().equals(result.leaseId())) {
                activeLease = new ActiveLease(activeLease.pos(), activeLease.leaseId(),
                        System.currentTimeMillis() + RENEW_INTERVAL_MILLIS);
                return;
            }
            if (result.status() != com.zhongbai233.net_music_can_play_bili.network
                    .ControlConsoleEditLeaseResultPacket.Status.GRANTED) {
                BlockPos queuedOpen = closingSave != null ? pendingOpen : null;
                if (closingSave != null) pendingOpen = null;
                closingSave = null;
                activeLease = null;
                if (minecraft.screen instanceof HolographicScreenConfigTestScreen
                        || minecraft.screen instanceof ControlConsoleGuideScreen) {
                    minecraft.setScreen(null);
                }
                if (minecraft.player != null) {
                    minecraft.player.sendSystemMessage(Component.literal("中控台编辑租约已失效"));
                }
                if (queuedOpen != null) openScreen(queuedOpen);
                return;
            }
        }
        if (pendingOpen == null || !pendingOpen.equals(result.pos())) {
            return;
        }
        pendingOpen = null;
        if (result.status() != com.zhongbai233.net_music_can_play_bili.network
                .ControlConsoleEditLeaseResultPacket.Status.GRANTED) {
            if (minecraft.player != null) {
                String message = result.status() == com.zhongbai233.net_music_can_play_bili.network
                        .ControlConsoleEditLeaseResultPacket.Status.BUSY
                        ? "该中控台正由其他玩家编辑" : "服务器拒绝打开中控台编辑器";
                minecraft.player.sendSystemMessage(Component.literal(message));
            }
            return;
        }
        activeLease = new ActiveLease(result.pos(), result.leaseId(),
                System.currentTimeMillis() + RENEW_INTERVAL_MILLIS);
        openGrantedWorkflow(result.pos(), minecraft);
    }

    private static void openGrantedWorkflow(BlockPos pos, Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (ClientPlayerPreferences.defaults().isControlConsoleGuideDismissed(minecraft.player.getUUID())) {
            openEditor(pos);
        } else {
            minecraft.setScreen(new ControlConsoleGuideScreen(pos));
        }
    }

    public static UUID leaseId(BlockPos pos) {
        return activeLease != null && activeLease.pos().equals(pos) ? activeLease.leaseId() : null;
    }

    public static boolean hasLease(BlockPos pos) {
        return leaseId(pos) != null;
    }

    public static void tickLease(BlockPos pos) {
        if (activeLease == null || !activeLease.pos().equals(pos)
                || System.currentTimeMillis() < activeLease.nextRenewMillis()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new com.zhongbai233.net_music_can_play_bili.network
                .ControlConsoleEditLeasePacket(pos,
                com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeasePacket.Action.RENEW,
                activeLease.leaseId()));
        activeLease = new ActiveLease(activeLease.pos(), activeLease.leaseId(),
                System.currentTimeMillis() + RENEW_INTERVAL_MILLIS);
    }

    public static void releaseLease(BlockPos pos) {
        if (activeLease == null || !activeLease.pos().equals(pos)) {
            return;
        }
        ClientPacketDistributor.sendToServer(new com.zhongbai233.net_music_can_play_bili.network
                .ControlConsoleEditLeasePacket(pos,
                com.zhongbai233.net_music_can_play_bili.network.ControlConsoleEditLeasePacket.Action.RELEASE,
                activeLease.leaseId()));
        if (closingSave != null && closingSave.pos().equals(pos)) {
            closingSave = null;
        }
        activeLease = null;
    }

    public static void clearLease() {
        pendingOpen = null;
        activeLease = null;
        closingSave = null;
    }

    /** Retains only the final document and request identity, never the closed Screen or its resources. */
    public static boolean deferCloseSave(BlockPos pos, UUID pendingOperation, ControlConsoleDocument latestDraft) {
        if (pos == null || pendingOperation == null || latestDraft == null) return false;
        if (closingSave != null) {
            return closingSave.pos().equals(pos) && closingSave.operationId().equals(pendingOperation);
        }
        Minecraft minecraft = Minecraft.getInstance();
        UUID leaseId = leaseId(pos);
        if (leaseId == null || minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive()) return false;
        closingSave = new ClosingSave(pos.immutable(), leaseId, pendingOperation, latestDraft, false,
                new WeakReference<>(minecraft.level), new WeakReference<>(minecraft.player),
                new WeakReference<>(minecraft.getConnection()), System.nanoTime() + CLOSE_SAVE_TIMEOUT_NANOS);
        return true;
    }

    public static boolean acceptClosingSaveResult(ControlConsoleConfigResultPacket result) {
        ClosingSave pending = closingSave;
        if (pending == null || !pending.pos().equals(result.pos()) || !pending.operationId().equals(result.operationId())) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (!pending.sameHost(minecraft) || !pending.leaseId().equals(leaseId(pending.pos()))) {
            discardClosingSave(pending);
            return true;
        }
        if (System.nanoTime() - pending.deadlineNanos() >= 0L) {
            finishClosingSave(pending, true);
            return true;
        }
        if ((result.status() != ControlConsoleConfigResultPacket.Status.APPLIED
                && result.status() != ControlConsoleConfigResultPacket.Status.DUPLICATE) || result.revision() < 0L) {
            finishClosingSave(pending, true);
            return true;
        }
        if (pending.finalRequest()) {
            finishClosingSave(pending, false);
            return true;
        }
        // Only an accepted first save supplies the next authoritative revision.
        ControlConsoleDocument draft = pending.draft().withRevision(result.revision());
        UUID operation = UUID.randomUUID();
        closingSave = new ClosingSave(pending.pos(), pending.leaseId(), operation, draft, true,
                pending.level(), pending.player(), pending.connection(), System.nanoTime() + CLOSE_SAVE_TIMEOUT_NANOS);
        ClientPacketDistributor.sendToServer(new ControlConsoleConfigPacket(pending.pos(), pending.leaseId(), operation,
                draft.revision(), draft.displayName(), draft.hardRangeX(), draft.hardRangeY(), draft.hardRangeZ(), draft.elements()));
        return true;
    }

    public static void tickClosingSave(long nowNanos) {
        ClosingSave pending = closingSave;
        if (pending == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!pending.sameHost(minecraft) || !pending.leaseId().equals(leaseId(pending.pos()))) {
            discardClosingSave(pending);
            return;
        }
        if (nowNanos - pending.deadlineNanos() >= 0L) {
            finishClosingSave(pending, true);
            return;
        }
        tickLease(pending.pos());
    }

    private static void discardClosingSave(ClosingSave pending) {
        closingSave = null;
        pendingOpen = null;
        if (pending.leaseId().equals(leaseId(pending.pos()))) activeLease = null;
    }

    private static void finishClosingSave(ClosingSave pending, boolean failed) {
        closingSave = null;
        BlockPos queuedOpen = pendingOpen;
        pendingOpen = null;
        releaseLease(pending.pos());
        Minecraft minecraft = Minecraft.getInstance();
        if (failed && minecraft.player != null) minecraft.player.sendSystemMessage(
                Component.literal("中控台保存未完成，请重新打开编辑器检查。"));
        if (queuedOpen != null) openScreen(queuedOpen);
    }

    public static void openEditor(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null && hasLease(pos)) {
            minecraft.setScreen(HolographicScreenConfigTestScreen.forControlConsole(pos));
        }
    }

    private record ActiveLease(BlockPos pos, UUID leaseId, long nextRenewMillis) {
    }

    private record ClosingSave(BlockPos pos, UUID leaseId, UUID operationId, ControlConsoleDocument draft,
            boolean finalRequest, WeakReference<Object> level, WeakReference<Object> player,
            WeakReference<Object> connection, long deadlineNanos) {
        boolean sameHost(Minecraft minecraft) {
            return minecraft.level != null && minecraft.player != null && minecraft.player.isAlive()
                    && minecraft.level == level.get() && minecraft.player == player.get()
                    && minecraft.getConnection() == connection.get();
        }
    }
}
