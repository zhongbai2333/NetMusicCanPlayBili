package com.zhongbai233.client_resource_diagnostics;

import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import static net.minecraft.commands.Commands.literal;

@EventBusSubscriber(value = Dist.CLIENT)
public final class ClientDiagnosticsCommands {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long MIB = 1024L * 1024L;

    private ClientDiagnosticsCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(literal("clientdiag")
                .executes(ClientDiagnosticsCommands::status)
                .then(literal("status").executes(ClientDiagnosticsCommands::status))
                .then(literal("report").executes(ClientDiagnosticsCommands::report))
                .then(literal("reset").executes(ClientDiagnosticsCommands::reset)));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        List<String> lines = format(DiagnosticsController.INSTANCE.snapshot(), 8);
        lines.forEach(line -> context.getSource().sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        List<String> lines = format(DiagnosticsController.INSTANCE.snapshot(), 64);
        try {
            Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("client-resource-diagnostics");
            Files.createDirectories(directory);
            Path report = directory.resolve("report-" + FILE_TIME.format(LocalDateTime.now()) + ".txt");
            Files.write(report, lines, StandardCharsets.UTF_8);
            context.getSource().sendSuccess(() -> Component.literal("诊断报告已写入: " + report), false);
            return 1;
        } catch (IOException error) {
            context.getSource().sendFailure(Component.literal("写入诊断报告失败: " + error));
            return 0;
        }
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        DiagnosticsController.INSTANCE.resetActivity();
        context.getSource().sendSuccess(() -> Component.literal("已重置 dirty/spike/peak 活动窗口；当前 GPU 存活量保留"), false);
        return 1;
    }

    private static List<String> format(DiagnosticsController.Snapshot snapshot, int ownerLimit) {
        List<String> lines = new ArrayList<>();
        lines.add("Client Resource Diagnostics");
        lines.add(snapshot.commitSupported()
                ? "processCommit=" + mib(snapshot.currentCommit()) + "MiB peak=" + mib(snapshot.peakCommit()) + "MiB spikes=" + snapshot.spikes().size()
                : "processCommit=UNSUPPORTED");
        lines.add("probeHits buffers=" + snapshot.bufferProbeHits() + " textures=" + snapshot.textureProbeHits()
            + " dirty=" + snapshot.dirtyProbeHits() + "（为0表示未命中，不能据此排除）");
        int count = 0;
        for (BoundedOwnerTable.OwnerSnapshot owner : snapshot.owners()) {
            if (count++ >= ownerLimit) {
                break;
            }
            lines.add("owner=" + owner.owner()
                    + " buffers=" + owner.bufferCount() + "/" + mib(owner.bufferBytes()) + "MiB peak=" + mib(owner.bufferPeak())
                    + " textures=" + owner.textureCount() + "/" + mib(owner.textureBytes()) + "MiB peak=" + mib(owner.texturePeak())
                    + " dirty=" + owner.dirtyCalls() + " sections=" + owner.dirtySections());
        }
        for (DiagnosticsController.Spike spike : snapshot.spikes()) {
            lines.add("spike uptime=" + String.format("%.3fs", spike.timestampNanos() / 1_000_000_000.0)
                    + " delta=" + mib(spike.deltaBytes()) + "MiB"
                    + " trackedBuffers=" + mib(spike.bufferBytes()) + "MiB"
                    + " trackedTextures=" + mib(spike.textureBytes()) + "MiB"
                    + " dirtySectionsSinceReset=" + spike.dirtySections());
        }
        lines.add("范围: Mojang GlBuffer/GlTexture logical bytes + LevelRenderer dirty；不含裸LWJGL资源和驱动/WDDM内部内存");
        return lines;
    }

    private static String mib(long bytes) {
        return String.format("%.1f", Math.max(0L, bytes) / (double)MIB);
    }
}