package com.zhongbai233.net_music_can_play_bili.item;

import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class HolographicGlassesItem extends Item {
    public static final int MAX_BOUND_MEDIA = 4;
    private static final String DATA_MP4_SCREENS = "holographic_glasses_mp4_screens";
    private static final String DATA_SCREEN_DISTANCE = "holographic_screen_distance";
    private static final String DATA_SCREEN_OFFSET_X = "holographic_screen_offset_x";
    private static final String DATA_SCREEN_OFFSET_Y = "holographic_screen_offset_y";
    private static final String DATA_SCREEN_HEIGHT = "holographic_screen_height";
    private static final String DATA_SCREEN_ASPECT = "holographic_screen_aspect";
    private static final String DATA_SCREEN_ROLL = "holographic_screen_roll";

    public HolographicGlassesItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) {
            player.setItemSlot(EquipmentSlot.HEAD, stack.copyWithCount(1));
            stack.shrink(1);
            player.sendSystemMessage(Component.translatable(
                    "message.net_music_can_play_bili.holographic_glasses.equipped"));
            return InteractionResult.SUCCESS;
        }
        player.sendSystemMessage(Component.translatable(
                "message.net_music_can_play_bili.holographic_glasses.equip_slot_occupied"));
        return InteractionResult.PASS;
    }

    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }

    public static boolean addOrUpdateBoundMedia(ItemStack stack, MediaSource source) {
        if (stack.isEmpty() || !HolographicGlassesAbility.has(stack) || source == null) {
            return false;
        }
        List<ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
        for (ScreenBinding binding : bindings) {
            if (source.equals(binding.source())) {
                writeScreenBindings(stack, bindings);
                return true;
            }
        }
        if (bindings.size() >= MAX_BOUND_MEDIA) {
            return false;
        }
        bindings.add(new ScreenBinding(source, defaultScreenConfig(bindings.size())));
        writeScreenBindings(stack, bindings);
        return true;
    }

    public static List<UUID> readBoundMp4s(ItemStack stack) {
        List<UUID> result = new ArrayList<>();
        for (ScreenBinding binding : readScreenBindings(stack)) {
            UUID deviceId = binding.deviceId();
            if (deviceId != null) {
                result.add(deviceId);
            }
        }
        return List.copyOf(result);
    }

    public static boolean boundToMp4(ItemStack stack, UUID deviceId) {
        return deviceId != null && readBoundMp4s(stack).contains(deviceId);
    }

    public static boolean boundToMedia(ItemStack stack, MediaSource source) {
        if (stack.isEmpty() || !HolographicGlassesAbility.has(stack) || source == null) {
            return false;
        }
        for (ScreenBinding binding : readScreenBindings(stack)) {
            if (source.equals(binding.source())) {
                return true;
            }
        }
        return false;
    }

    public static int clearAllBoundMedia(ItemStack stack) {
        if (stack.isEmpty() || !HolographicGlassesAbility.has(stack)) {
            return 0;
        }
        int count = readScreenBindings(stack).size();
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.remove(DATA_MP4_SCREENS)));
        return count;
    }

    public static boolean clearBoundMp4(ItemStack stack, UUID deviceId) {
        if (stack.isEmpty() || !HolographicGlassesAbility.has(stack) || deviceId == null) {
            return false;
        }
        List<ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
        boolean removed = bindings.removeIf(binding -> deviceId.equals(binding.deviceId()));
        if (removed) {
            writeScreenBindings(stack, bindings);
        }
        return removed;
    }

    public static boolean clearBoundMedia(ItemStack stack, MediaSource source) {
        if (stack.isEmpty() || !HolographicGlassesAbility.has(stack) || source == null) {
            return false;
        }
        if (source.isMp4()) {
            return clearBoundMp4(stack, source.mp4DeviceId());
        }
        List<ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
        boolean removed = bindings.removeIf(binding -> source.equals(binding.source()));
        if (removed) {
            writeScreenBindings(stack, bindings);
        }
        return removed;
    }

    public static ScreenConfig defaultScreenConfig() {
        return defaultScreenConfig(0);
    }

    public static ScreenConfig defaultScreenConfig(int index) {
        float offsetX = switch (Math.max(0, index)) {
            case 1 -> -0.85F;
            case 2 -> 0.85F;
            case 3 -> 0.0F;
            default -> 0.0F;
        };
        float offsetY = Math.max(0, index) == 3 ? HolographicScreenSettings.DEFAULT_HEIGHT
                : HolographicScreenSettings.DEFAULT_OFFSET_Y;
        return new ScreenConfig(HolographicScreenSettings.DEFAULT_DISTANCE, offsetX, offsetY,
                HolographicScreenSettings.DEFAULT_HEIGHT, HolographicScreenSettings.DEFAULT_ASPECT,
                HolographicScreenSettings.DEFAULT_ROLL);
    }

    public static ScreenConfig readScreenConfig(ItemStack stack) {
        List<ScreenBinding> bindings = readScreenBindings(stack);
        if (!bindings.isEmpty()) {
            return bindings.getFirst().config();
        }
        return defaultScreenConfig();
    }

    public static void writeScreenConfig(ItemStack stack, ScreenConfig config) {
        writeScreenConfigs(stack, List.of(config));
    }

    public static List<ScreenBinding> readScreenBindings(ItemStack stack) {
        List<ScreenBinding> result = new ArrayList<>();
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            CompoundTag tag = customData.copyTag();
            for (net.minecraft.nbt.Tag entry : tag.getListOrEmpty(DATA_MP4_SCREENS)) {
                if (!(entry instanceof CompoundTag compound)) {
                    continue;
                }
                MediaSource source = MediaBindingData.readSource(compound);
                if (source == null) {
                    continue;
                }
                result.add(new ScreenBinding(source, readConfig(compound, defaultScreenConfig(result.size()))));
                if (result.size() >= MAX_BOUND_MEDIA) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    public static List<ScreenConfig> readScreenConfigs(ItemStack stack) {
        List<ScreenConfig> result = new ArrayList<>();
        List<ScreenBinding> bindings = readScreenBindings(stack);
        if (!bindings.isEmpty()) {
            for (ScreenBinding binding : bindings) {
                result.add(binding.config());
            }
        }
        return List.copyOf(result);
    }

    public static void writeScreenConfigs(ItemStack stack, List<ScreenConfig> configs) {
        if (stack.isEmpty() || !HolographicGlassesAbility.has(stack) || configs == null) {
            return;
        }
        List<ScreenBinding> bindings = new ArrayList<>(readScreenBindings(stack));
        int count = Math.min(Math.min(bindings.size(), configs.size()), MAX_BOUND_MEDIA);
        List<ScreenBinding> updated = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            updated.add(new ScreenBinding(bindings.get(i).source(), configs.get(i).clamped()));
        }
        if (!updated.isEmpty()) {
            writeScreenBindings(stack, updated);
        }
    }

    private static ScreenConfig readConfig(CompoundTag tag, ScreenConfig defaults) {
        return new ScreenConfig(
                tag.getFloat(DATA_SCREEN_DISTANCE).orElse(defaults.distance()),
                tag.getFloat(DATA_SCREEN_OFFSET_X).orElse(defaults.offsetX()),
                tag.getFloat(DATA_SCREEN_OFFSET_Y).orElse(defaults.offsetY()),
                tag.getFloat(DATA_SCREEN_HEIGHT).orElse(defaults.height()),
                tag.getFloat(DATA_SCREEN_ASPECT).orElse(defaults.aspect()),
                tag.getFloat(DATA_SCREEN_ROLL).orElse(defaults.roll())).clamped();
    }

    private static void writeScreenBindings(ItemStack stack, List<ScreenBinding> bindings) {
        ListTag list = new ListTag();
        int count = Math.min(bindings.size(), MAX_BOUND_MEDIA);
        for (int i = 0; i < count; i++) {
            ScreenBinding binding = bindings.get(i);
            CompoundTag entry = new CompoundTag();
            MediaBindingData.writeSource(entry, binding.source());
            writeConfig(entry, binding.config().clamped());
            list.add(entry);
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> {
                    tag.put(DATA_MP4_SCREENS, list);
                }));
    }

    private static void writeConfig(CompoundTag tag, ScreenConfig config) {
        tag.putFloat(DATA_SCREEN_DISTANCE, config.distance());
        tag.putFloat(DATA_SCREEN_OFFSET_X, config.offsetX());
        tag.putFloat(DATA_SCREEN_OFFSET_Y, config.offsetY());
        tag.putFloat(DATA_SCREEN_HEIGHT, config.height());
        tag.putFloat(DATA_SCREEN_ASPECT, config.aspect());
        tag.putFloat(DATA_SCREEN_ROLL, config.roll());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.holographic_glasses.protection")
                .withStyle(ChatFormatting.GRAY));
        List<ScreenBinding> bindings = readScreenBindings(stack);
        if (!bindings.isEmpty()) {
            MediaSource source = bindings.getFirst().source();
            if (source != null) {
                tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.holographic_glasses.media",
                        source.shortName()).withStyle(ChatFormatting.GRAY));
            }
            int count = bindings.size();
            if (count > 1) {
                tooltip.accept(
                        Component.translatable("tooltip.net_music_can_play_bili.holographic_glasses.media_count", count)
                                .withStyle(ChatFormatting.GRAY));
            }
        }
    }

    public record ScreenBinding(MediaSource source, ScreenConfig config) {
        public UUID deviceId() {
            return source != null && source.isMp4() ? source.mp4DeviceId() : null;
        }
    }

    public record ScreenConfig(float distance, float offsetX, float offsetY, float height, float aspect, float roll) {
        private ScreenConfig clamped() {
            return new ScreenConfig(
                    HolographicScreenSettings.clampDistance(distance),
                    HolographicScreenSettings.clampOffsetX(offsetX),
                    HolographicScreenSettings.clampOffsetY(offsetY),
                    HolographicScreenSettings.clampHeight(height),
                    HolographicScreenSettings.clampAspect(aspect),
                    HolographicScreenSettings.clampRoll(roll));
        }
    }
}
