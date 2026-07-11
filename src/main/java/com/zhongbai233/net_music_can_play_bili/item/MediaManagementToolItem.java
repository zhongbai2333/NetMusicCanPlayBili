package com.zhongbai233.net_music_can_play_bili.item;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu.ReportSourceInfo;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceIdentity;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class MediaManagementToolItem extends Item {
    private static final String TAG_TARGET_KIND = "media_tool_target_kind";
    private static final String TAG_MP4_DEVICE_ID = "media_tool_mp4_device_id";
    private static final String TARGET_KIND_MP4 = "mp4";

    public MediaManagementToolItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        ItemStack tool = player.getItemInHand(hand);
        ItemStack offhand = player.getOffhandItem();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (offhand.getItem() instanceof MP4Item) {
            UUID deviceId = MP4DeviceIdentity.getOrCreateUnique((ServerLevel) serverPlayer.level(), serverPlayer,
                    offhand);
            if (deviceId == null) {
                return InteractionResult.PASS;
            }
            if (player.isShiftKeyDown()) {
                openReportMenu(serverPlayer, sourceListFor(PlaybackAuditManager.findMp4(
                        serverPlayer.level().getServer(), deviceId), serverPlayer));
                return InteractionResult.SUCCESS;
            }
            rememberMp4(tool, deviceId);
            openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.MP4, null, null);
            return InteractionResult.SUCCESS;
        }
        if (offhand.getItem() instanceof PadItem) {
            UUID deviceId = PadItem.getOrCreateDeviceId(offhand);
            if (deviceId == null) {
                return InteractionResult.PASS;
            }
            openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.PAD, null, deviceId);
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown()) {
            openReportMenu(serverPlayer, nearbyAudibleSources(serverPlayer));
            return InteractionResult.SUCCESS;
        }
        openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.MP4, null, null);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        BlockPos pos = context.getClickedPos();
        if (player.isShiftKeyDown()) {
            openReportMenu(serverPlayer, sourceListFor(PlaybackAuditManager.findModernTurntable(
                    serverPlayer.level().getServer(), serverLevel, pos), serverPlayer));
            return InteractionResult.CONSUME;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ModernTurntableBlockEntity) {
            if (!player.mayBuild()) {
                return InteractionResult.PASS;
            }
            openBindingMenu(serverPlayer, MediaToolBindingMenu.TargetKind.TURNTABLE, pos, null);
            return InteractionResult.CONSUME;
        }
        player.sendSystemMessage(Component.translatable(
                "message.net_music_can_play_bili.media_tool.unsupported_target").withStyle(ChatFormatting.RED));
        return InteractionResult.CONSUME;
    }

    private static void openBindingMenu(ServerPlayer player, MediaToolBindingMenu.TargetKind targetKind,
            BlockPos targetPos,
            UUID mp4DeviceId) {
        player.openMenu(new MediaToolMenuProvider(targetKind, targetPos, mp4DeviceId));
    }

    private static void openReportMenu(ServerPlayer player, List<ReportSourceInfo> sources) {
        if (sources == null || sources.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.media_tool.no_active_source").withStyle(ChatFormatting.RED));
            return;
        }
        player.openMenu(new MediaToolReportMenuProvider(sources));
    }

    private record MediaToolMenuProvider(MediaToolBindingMenu.TargetKind targetKind, BlockPos targetPos,
            UUID mp4DeviceId)
            implements net.minecraft.world.MenuProvider {
        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.net_music_can_play_bili.media_tool_binding");
        }

        @Override
        public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId,
                net.minecraft.world.entity.player.Inventory inventory, Player player) {
            return new MediaToolBindingMenu(containerId, inventory, targetKind, targetPos, mp4DeviceId);
        }

        @Override
        public void writeClientSideData(net.minecraft.world.inventory.AbstractContainerMenu menu,
                net.minecraft.network.RegistryFriendlyByteBuf buffer) {
            MediaToolBindingMenu.writeClientData(buffer, targetKind, targetPos, mp4DeviceId);
        }
    }

    private record MediaToolReportMenuProvider(List<ReportSourceInfo> sources)
            implements net.minecraft.world.MenuProvider {
        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.net_music_can_play_bili.media_tool_report");
        }

        @Override
        public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId,
                net.minecraft.world.entity.player.Inventory inventory, Player player) {
            return new MediaToolReportMenu(containerId, inventory, sources);
        }

        @Override
        public void writeClientSideData(net.minecraft.world.inventory.AbstractContainerMenu menu,
                net.minecraft.network.RegistryFriendlyByteBuf buffer) {
            MediaToolReportMenu.writeClientData(buffer, sources);
        }
    }

    private static List<ReportSourceInfo> nearbyAudibleSources(ServerPlayer player) {
        return PlaybackAuditManager.snapshot(player.level().getServer()).stream()
                .filter(source -> player.level().dimension().equals(source.levelKey()))
                .filter(source -> source.sourcePos()
                        .distToCenterSqr(player.position()) <= AudioLinkData.MP4_HEADPHONE_RANGE_SQUARED)
                .sorted(Comparator.comparingDouble(source -> source.sourcePos().distToCenterSqr(player.position())))
                .limit(MediaToolReportMenu.MAX_SOURCES)
                .map(source -> MediaToolReportMenu.fromActiveSource(player.level().getServer(), player, source))
                .toList();
    }

    private static List<ReportSourceInfo> sourceListFor(PlaybackAuditManager.ActiveSource source, ServerPlayer player) {
        return source == null ? List.of()
                : List.of(MediaToolReportMenu.fromActiveSource(player.level().getServer(), player, source));
    }

    private static void rememberMp4(ItemStack tool, UUID deviceId) {
        tool.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.putString(TAG_TARGET_KIND, TARGET_KIND_MP4);
            tag.putString(TAG_MP4_DEVICE_ID, deviceId.toString());
        }));
    }

    private static UUID readRememberedMp4(ItemStack tool) {
        CustomData customData = tool.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        CompoundTag tag = customData.copyTag();
        if (!TARGET_KIND_MP4.equals(tag.getString(TAG_TARGET_KIND).orElse(""))) {
            return null;
        }
        String value = tag.getString(TAG_MP4_DEVICE_ID).orElse("");
        try {
            return value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String shortUuid(UUID deviceId) {
        String value = deviceId != null ? deviceId.toString() : "";
        return value.length() > 8 ? value.substring(0, 8) : value;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.media_tool.binding")
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.media_tool.report")
                .withStyle(ChatFormatting.GRAY));
        UUID deviceId = readRememberedMp4(stack);
        if (deviceId != null) {
            tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.media_tool.mp4_target",
                    shortUuid(deviceId)).withStyle(ChatFormatting.GOLD));
        }
    }
}
