package com.zhongbai233.net_music_can_play_bili.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.client.PadClientHooks;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import com.zhongbai233.net_music_can_play_bili.network.PadDocumentStore;
import com.zhongbai233.net_music_can_play_bili.network.PadStateMirrorPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class PadItem extends Item {
    private static final String DATA_DEVICE_ID = "pad_device_id";
    private static final String DATA_TITLE = "pad_title";
    private static final String DATA_AUTHOR = "pad_author";
    private static final String DATA_LOCKED = "pad_locked";
    private static final String DATA_UPDATED_AT = "pad_updated_at";
    private static final String DATA_SEQUENCE = "pad_sequence";
    private static final String DATA_MEDIA = "pad_media_entries";
    private static final String DATA_MEDIA_ID = "id";
    private static final String DATA_MEDIA_STACK = "stack";
    private static final String DATA_POINTS = "pad_trigger_points";
    private static final String DATA_POINT_ID = "id";
    private static final String DATA_POINT_NAME = "name";
    private static final String DATA_POINT_X = "x";
    private static final String DATA_POINT_Y = "y";
    private static final String DATA_POINT_Z = "z";
    private static final String DATA_POINT_RADIUS = "radius";
    private static final String DATA_POINT_MEDIA_ID = "mediaId";
    private static final String DATA_POINT_MODE = "mode";
    private static final String DATA_POINT_LOOP = "loop";
    private static final String DATA_POINT_VOLUME = "volume";
    private static final String DATA_POINT_VISIBLE = "visible";

    public PadItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand == InteractionHand.OFF_HAND
                || (hand == InteractionHand.MAIN_HAND && player.getOffhandItem().getItem() instanceof PadItem)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            getOrCreateDeviceId(player.getItemInHand(hand));
        } else {
            PadClientHooks.openFocusScreen(hand);
        }
        return InteractionResult.CONSUME;
    }

    public static UUID readDeviceId(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        String value = customData.copyTag().getString(DATA_DEVICE_ID).orElse("");
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static UUID getOrCreateDeviceId(ItemStack stack) {
        UUID existing = readDeviceId(stack);
        if (existing != null) {
            return existing;
        }
        UUID created = UUID.randomUUID();
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.putString(DATA_DEVICE_ID, created.toString())));
        return created;
    }

    public static void writeDeviceId(ItemStack stack, UUID deviceId) {
        if (!isPad(stack) || deviceId == null) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.putString(DATA_DEVICE_ID, deviceId.toString())));
    }

    public static ItemStack findByDeviceId(Player player, UUID deviceId) {
        if (player == null || deviceId == null) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> stacks = findAllByDeviceId(player, deviceId);
        return stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0);
    }

    public static List<ItemStack> findAllByDeviceId(Player player, UUID deviceId) {
        List<ItemStack> matches = new ArrayList<>();
        if (player == null || deviceId == null) {
            return matches;
        }
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        if (deviceId.equals(readDeviceId(carried))) {
            matches.add(carried);
        }
        if (deviceId.equals(readDeviceId(player.getMainHandItem()))) {
            matches.add(player.getMainHandItem());
        }
        if (deviceId.equals(readDeviceId(player.getOffhandItem()))) {
            matches.add(player.getOffhandItem());
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (deviceId.equals(readDeviceId(stack))) {
                matches.add(stack);
            }
        }
        return matches;
    }

    public static void writeDocumentToUnlockedCopies(Player player, UUID deviceId, PadDocument document) {
        if (player == null || deviceId == null || document == null) {
            return;
        }
        for (ItemStack stack : findAllByDeviceId(player, deviceId)) {
            if (isPad(stack) && !readLocked(stack)) {
                writeDeviceId(stack, deviceId);
                writeDocument(stack, document.copyWithLocked(false));
            }
        }
    }

    public static void writeDocumentToLockedCopies(Player player, UUID deviceId, PadDocument document) {
        if (player == null || deviceId == null || document == null) {
            return;
        }
        for (ItemStack stack : findAllByDeviceId(player, deviceId)) {
            if (isPad(stack) && readLocked(stack)) {
                writeDeviceId(stack, deviceId);
                writeDocument(stack, document.copyWithLocked(true));
            }
        }
    }

    public static boolean isPad(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof PadItem;
    }

    public static boolean isNetMusicDisc(ItemStack stack) {
        return !stack.isEmpty() && ItemMusicCD.getSongInfo(stack) != null;
    }

    public static PadDocument readDocument(ItemStack stack) {
        PadDocument legacy = readLegacyDocument(stack);
        if (legacy != null) {
            return legacy;
        }
        return PadDocument.DEFAULT.copyWithLocked(readLocked(stack));
    }

    public static PadDocument readLegacyDocument(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return null;
        }
        CompoundTag tag = customData.copyTag();
        if (!hasLegacyDocument(tag)) {
            return null;
        }
        List<PadMediaEntry> media = readMediaEntries(tag);
        List<PadTriggerPoint> points = readTriggerPoints(tag);
        return new PadDocument(tag.getString(DATA_TITLE).orElse(""), tag.getString(DATA_AUTHOR).orElse(""),
                tag.getBoolean(DATA_LOCKED).orElse(false), tag.getLong(DATA_UPDATED_AT).orElse(0L),
                tag.getLong(DATA_SEQUENCE).orElse(0L), null, media, points);
    }

    public static void writeDocument(ItemStack stack, PadDocument document) {
        if (!isPad(stack) || document == null) {
            return;
        }
        writeLegacyDocument(stack, document);
    }

    public static boolean readLocked(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        return customData != null && !customData.isEmpty()
                && customData.copyTag().getBoolean(DATA_LOCKED).orElse(false);
    }

    public static void writeLocked(ItemStack stack, boolean locked) {
        if (!isPad(stack)) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.putBoolean(DATA_LOCKED, locked)));
    }

    public static void stripDocumentData(ItemStack stack) {
        if (!isPad(stack)) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(PadItem::removeDocumentData));
    }

    private static boolean hasLegacyDocument(CompoundTag tag) {
        return tag.contains(DATA_MEDIA) || tag.contains(DATA_POINTS) || tag.contains(DATA_TITLE)
                || tag.contains(DATA_AUTHOR) || tag.contains(DATA_UPDATED_AT) || tag.contains(DATA_SEQUENCE);
    }

    private static void removeDocumentData(CompoundTag tag) {
        tag.remove(DATA_TITLE);
        tag.remove(DATA_AUTHOR);
        tag.remove(DATA_UPDATED_AT);
        tag.remove(DATA_SEQUENCE);
        tag.remove(DATA_MEDIA);
        tag.remove(DATA_POINTS);
    }

    public static boolean addDisc(ItemStack padStack, ItemStack discStack) {
        if (!isPad(padStack) || !isNetMusicDisc(discStack)) {
            return false;
        }
        PadDocument document = readDocument(padStack);
        if (document.locked() || document.nextFreeMediaId() < 0) {
            return false;
        }
        writeDocument(padStack, document.withAddedMedia(BiliSongInfoSanitizer.sanitizeDisc(discStack)));
        discStack.shrink(1);
        return true;
    }

    private static boolean addDisc(ServerPlayer player, ItemStack padStack, ItemStack discStack) {
        if (!isPad(padStack) || !isNetMusicDisc(discStack) || !(player.level() instanceof ServerLevel level)) {
            return addDisc(padStack, discStack);
        }
        UUID deviceId = getOrCreateDeviceId(padStack);
        PadDocument document = PadDocumentStore.getOrCreate(level, deviceId, padStack)
                .copyWithLocked(readLocked(padStack));
        if (document.locked() || document.nextFreeMediaId() < 0) {
            return false;
        }
        PadDocument updated = document.withAddedMedia(BiliSongInfoSanitizer.sanitizeDisc(discStack))
                .copyWithLocked(false);
        PadDocumentStore.update(level, deviceId, updated);
        writeDocument(padStack, updated);
        discStack.shrink(1);
        syncDocumentToPlayer(player, deviceId, updated);
        return true;
    }

    public static ItemStack removeLastDisc(ItemStack padStack) {
        if (!isPad(padStack)) {
            return ItemStack.EMPTY;
        }
        PadDocument document = readDocument(padStack);
        if (document.locked() || document.mediaEntries().isEmpty()) {
            return ItemStack.EMPTY;
        }
        PadMediaEntry last = document.mediaEntries().get(document.mediaEntries().size() - 1);
        writeDocument(padStack, document.withRemovedMedia(last.mediaId()));
        return last.disc().copyWithCount(1);
    }

    private static ItemStack removeLastDisc(ServerPlayer player, ItemStack padStack) {
        if (!isPad(padStack) || !(player.level() instanceof ServerLevel level)) {
            return removeLastDisc(padStack);
        }
        UUID deviceId = getOrCreateDeviceId(padStack);
        PadDocument document = PadDocumentStore.getOrCreate(level, deviceId, padStack)
                .copyWithLocked(readLocked(padStack));
        if (document.locked() || document.mediaEntries().isEmpty()) {
            return ItemStack.EMPTY;
        }
        PadMediaEntry last = document.mediaEntries().get(document.mediaEntries().size() - 1);
        PadDocument updated = document.withRemovedMedia(last.mediaId()).copyWithLocked(false);
        PadDocumentStore.update(level, deviceId, updated);
        writeDocument(padStack, updated);
        syncDocumentToPlayer(player, deviceId, updated);
        return last.disc().copyWithCount(1);
    }

    private static void syncDocumentToPlayer(ServerPlayer player, UUID deviceId, PadDocument document) {
        if (player == null || deviceId == null || document == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new PadStateMirrorPacket(deviceId, document, player.level().getGameTime()));
    }

    @Override
    public boolean overrideStackedOnOther(ItemStack padStack, Slot slot, ClickAction action, Player player) {
        if (action == ClickAction.PRIMARY) {
            ItemStack slotStack = slot.getItem();
            if (player instanceof ServerPlayer serverPlayer && addDisc(serverPlayer, padStack, slotStack)
                    || !(player instanceof ServerPlayer) && addDisc(padStack, slotStack)) {
                if (slotStack.isEmpty()) {
                    slot.set(ItemStack.EMPTY);
                }
                slot.setChanged();
                return true;
            }
            return false;
        }
        if (action == ClickAction.SECONDARY && !slot.hasItem()) {
            if (player instanceof ServerPlayer serverPlayer) {
                ItemStack removed = removeLastDisc(serverPlayer, padStack);
                if (removed.isEmpty()) {
                    return false;
                }
                slot.set(removed);
                slot.setChanged();
                return true;
            }
            ItemStack removed = removeLastDisc(padStack);
            if (!removed.isEmpty()) {
                slot.set(removed);
                slot.setChanged();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack padStack, ItemStack carriedStack, Slot slot, ClickAction action,
            Player player, SlotAccess carriedAccess) {
        if (action == ClickAction.PRIMARY
                && (player instanceof ServerPlayer serverPlayer && addDisc(serverPlayer, padStack, carriedStack)
                || !(player instanceof ServerPlayer) && addDisc(padStack, carriedStack))) {
            carriedAccess.set(carriedStack);
            return true;
        }
        if (action == ClickAction.SECONDARY && carriedStack.isEmpty()) {
            if (player instanceof ServerPlayer serverPlayer) {
                ItemStack removed = removeLastDisc(serverPlayer, padStack);
                if (removed.isEmpty()) {
                    return false;
                }
                carriedAccess.set(removed);
                return true;
            }
            ItemStack removed = removeLastDisc(padStack);
            if (!removed.isEmpty()) {
                carriedAccess.set(removed);
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        PadDocument document = readDocument(stack);
        tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.pad.summary",
                document.mediaEntries().size(), document.triggerPoints().size()).withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable(document.locked()
                ? "tooltip.net_music_can_play_bili.pad.locked"
                : "tooltip.net_music_can_play_bili.pad.draft").withStyle(document.locked() ? ChatFormatting.GOLD
                        : ChatFormatting.GREEN));
        tooltip.accept(Component.translatable("tooltip.net_music_can_play_bili.pad.playback_target")
                .withStyle(ChatFormatting.DARK_AQUA));
    }

    private static List<PadMediaEntry> readMediaEntries(CompoundTag tag) {
        List<PadMediaEntry> result = new ArrayList<>();
        for (net.minecraft.nbt.Tag entry : tag.getListOrEmpty(DATA_MEDIA)) {
            if (!(entry instanceof CompoundTag compound)) {
                continue;
            }
            int id = compound.getInt(DATA_MEDIA_ID).orElse(0);
            compound.read(DATA_MEDIA_STACK, ItemStack.OPTIONAL_CODEC)
                    .filter(PadItem::isNetMusicDisc)
                    .ifPresent(stack -> result.add(new PadMediaEntry(id, BiliSongInfoSanitizer.sanitizeDisc(stack))));
        }
        return result;
    }

    private static void writeLegacyDocument(ItemStack stack, PadDocument document) {
        if (!isPad(stack) || document == null) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, customData -> customData.update(tag -> {
            tag.putString(DATA_TITLE, document.title());
            tag.putString(DATA_AUTHOR, document.author());
            tag.putBoolean(DATA_LOCKED, document.locked());
            tag.putLong(DATA_UPDATED_AT, document.updatedAtMillis());
            tag.putLong(DATA_SEQUENCE, document.sequence());

            ListTag media = new ListTag();
            for (PadMediaEntry entry : document.mediaEntries()) {
                if (entry == null || !isNetMusicDisc(entry.disc())) {
                    continue;
                }
                CompoundTag compound = new CompoundTag();
                compound.putInt(DATA_MEDIA_ID, entry.mediaId());
                compound.store(DATA_MEDIA_STACK, ItemStack.OPTIONAL_CODEC,
                        BiliSongInfoSanitizer.sanitizeDisc(entry.disc()));
                media.add(compound);
            }
            tag.put(DATA_MEDIA, media);

            ListTag points = new ListTag();
            for (PadTriggerPoint point : document.triggerPoints()) {
                if (point == null) {
                    continue;
                }
                CompoundTag compound = new CompoundTag();
                compound.putString(DATA_POINT_ID, point.pointId().toString());
                compound.putString(DATA_POINT_NAME, point.name());
                compound.putDouble(DATA_POINT_X, point.x());
                compound.putDouble(DATA_POINT_Y, point.y());
                compound.putDouble(DATA_POINT_Z, point.z());
                compound.putInt(DATA_POINT_RADIUS, point.radiusBlocks());
                compound.putInt(DATA_POINT_MEDIA_ID, point.mediaId());
                compound.putString(DATA_POINT_MODE, point.triggerMode().name());
                compound.putBoolean(DATA_POINT_LOOP, point.loop());
                compound.putInt(DATA_POINT_VOLUME, point.volumePerMille());
                compound.putBoolean(DATA_POINT_VISIBLE, point.visible());
                points.add(compound);
            }
            tag.put(DATA_POINTS, points);
        }));
    }

    private static List<PadTriggerPoint> readTriggerPoints(CompoundTag tag) {
        List<PadTriggerPoint> result = new ArrayList<>();
        for (net.minecraft.nbt.Tag entry : tag.getListOrEmpty(DATA_POINTS)) {
            if (!(entry instanceof CompoundTag compound)) {
                continue;
            }
            UUID pointId = parseUuid(compound.getString(DATA_POINT_ID).orElse(""));
            result.add(new PadTriggerPoint(pointId, compound.getString(DATA_POINT_NAME).orElse(""),
                    compound.getDouble(DATA_POINT_X).orElse(0.0D), compound.getDouble(DATA_POINT_Y).orElse(0.0D),
                    compound.getDouble(DATA_POINT_Z).orElse(0.0D), compound.getInt(DATA_POINT_RADIUS).orElse(8),
                    compound.getInt(DATA_POINT_MEDIA_ID).orElse(0),
                    PadTriggerMode.byName(compound.getString(DATA_POINT_MODE).orElse("")),
                    compound.getBoolean(DATA_POINT_LOOP).orElse(false), compound.getInt(DATA_POINT_VOLUME).orElse(1000),
                    compound.getBoolean(DATA_POINT_VISIBLE).orElse(true)));
        }
        return result;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? UUID.randomUUID() : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return UUID.randomUUID();
        }
    }
}