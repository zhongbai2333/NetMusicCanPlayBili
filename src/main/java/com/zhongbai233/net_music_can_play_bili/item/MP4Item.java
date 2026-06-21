package com.zhongbai233.net_music_can_play_bili.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.client.tooltip.MP4QueueTooltip;
import com.zhongbai233.net_music_can_play_bili.client.MP4ClientHooks;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceIdentity;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceStateStore;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MP4 手持媒体设备物品。
 * <p>
 * 负责保存稳定的设备 ID 和 NetMusic 唱片队列，并在客户端打开聚焦的手持设备界面。
 * </p>
 */
public class MP4Item extends Item {
    private static final String DATA_DEVICE_ID = "mp4_device_id";
    private static final String DATA_QUEUE = "mp4_queue";
    private static final String DATA_QUEUE_STACK = "stack";
    public static final int MAX_QUEUE_SIZE = 18;

    public MP4Item(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand == InteractionHand.OFF_HAND
                || (hand == InteractionHand.MAIN_HAND && player.getOffhandItem().getItem() instanceof MP4Item)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            MP4DeviceIdentity.getOrCreateUnique((ServerLevel) level, (ServerPlayer) player, player.getItemInHand(hand));
        }
        if (level.isClientSide()) {
            MP4ClientHooks.openFocusScreen(hand);
        }
        return InteractionResult.CONSUME;
    }

    public static boolean isNetMusicDisc(ItemStack stack) {
        return !stack.isEmpty() && ItemMusicCD.getSongInfo(stack) != null;
    }

    public static ItemStack findPlayableInInventory(Player player) {
        return findInInventory(player, true);
    }

    public static ItemStack findAnyInInventory(Player player) {
        return findInInventory(player, false);
    }

    public static ItemStack findByDeviceId(Player player, UUID deviceId) {
        if (player == null || deviceId == null) {
            return ItemStack.EMPTY;
        }
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        if (carried.getItem() instanceof MP4Item && deviceId.equals(readDeviceId(carried))) {
            return carried;
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() instanceof MP4Item && deviceId.equals(readDeviceId(stack))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
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
        if (stack.isEmpty() || !(stack.getItem() instanceof MP4Item) || deviceId == null) {
            return;
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                customData -> customData.update(tag -> tag.putString(DATA_DEVICE_ID, deviceId.toString())));
    }

    private static ItemStack findInInventory(Player player, boolean preferPlaying) {
        if (player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack carried = player.containerMenu != null ? player.containerMenu.getCarried() : ItemStack.EMPTY;
        if (carried.getItem() instanceof MP4Item && (!preferPlaying || isPlaying(player, carried))) {
            return carried;
        }
        ItemStack fallback = carried.getItem() instanceof MP4Item ? carried : ItemStack.EMPTY;
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() instanceof MP4Item && (!preferPlaying || isPlaying(player, mainHand))) {
            return mainHand;
        }
        if (fallback.isEmpty() && mainHand.getItem() instanceof MP4Item) {
            fallback = mainHand;
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof MP4Item)) {
                continue;
            }
            if (preferPlaying && isPlaying(player, stack)) {
                return stack;
            }
            if (fallback.isEmpty()) {
                fallback = stack;
            }
        }
        return fallback;
    }

    private static boolean isPlaying(Player player, ItemStack stack) {
        UUID deviceId = readDeviceId(stack);
        if (deviceId == null) {
            return false;
        }
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level) {
            return MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state().playing();
        }
        return MP4DeviceStateStore.get(deviceId).state().playing();
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        List<ItemStack> queue = readQueue(stack);
        if (queue.isEmpty()) {
            return Optional.empty();
        }
        List<String> titles = new ArrayList<>(queue.size());
        for (ItemStack disc : queue) {
            @SuppressWarnings("null")
            ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(disc);
            if (songInfo != null) {
                titles.add(songInfo.songName == null || songInfo.songName.isBlank()
                        ? "NetMusic 唱片 " + (titles.size() + 1)
                        : songInfo.songName);
            }
        }
        if (titles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MP4QueueTooltip(titles, MP4ClientHooks.selectedQueueIndex(stack)));
    }

    @Override
    public boolean overrideStackedOnOther(ItemStack mp4Stack, Slot slot, ClickAction action, Player player) {
        if (action == ClickAction.PRIMARY) {
            ItemStack slotStack = slot.getItem();
            if (isNetMusicDisc(slotStack) && addDisc(mp4Stack, slotStack)) {
                syncQueueCopy(player, mp4Stack);
                if (slotStack.isEmpty()) {
                    slot.set(ItemStack.EMPTY);
                }
                slot.setChanged();
                return true;
            }
            return false;
        }
        if (action == ClickAction.SECONDARY && !slot.hasItem()) {
            ItemStack removed = removeSelectedOrLastDisc(mp4Stack);
            if (!removed.isEmpty()) {
                syncQueueCopy(player, mp4Stack);
                slot.set(removed);
                slot.setChanged();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack mp4Stack, ItemStack carriedStack, Slot slot, ClickAction action,
            Player player, SlotAccess carriedAccess) {
        if (action == ClickAction.PRIMARY && isNetMusicDisc(carriedStack) && addDisc(mp4Stack, carriedStack)) {
            syncQueueCopy(player, mp4Stack);
            carriedAccess.set(carriedStack);
            return true;
        }
        if (action == ClickAction.SECONDARY && carriedStack.isEmpty()) {
            ItemStack removed = removeSelectedOrLastDisc(mp4Stack);
            if (!removed.isEmpty()) {
                syncQueueCopy(player, mp4Stack);
                carriedAccess.set(removed);
                return true;
            }
        }
        return false;
    }

    public static List<ItemStack> readQueue(ItemStack stack) {
        NonNullList<ItemStack> result = NonNullList.create();
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) {
            return result;
        }
        for (net.minecraft.nbt.Tag entry : customData.copyTag().getListOrEmpty(DATA_QUEUE)) {
            if (!(entry instanceof CompoundTag compound)) {
                continue;
            }
            compound.read(DATA_QUEUE_STACK, ItemStack.OPTIONAL_CODEC)
                    .filter(MP4Item::isNetMusicDisc)
                    .ifPresent(itemStack -> result.add(BiliSongInfoSanitizer.sanitizeDisc(itemStack)));
            if (result.size() >= MAX_QUEUE_SIZE) {
                break;
            }
        }
        return result;
    }

    public static int queueSize(ItemStack stack) {
        return readQueue(stack).size();
    }

    public static boolean addDisc(ItemStack mp4Stack, ItemStack discStack) {
        if (mp4Stack.isEmpty() || !(mp4Stack.getItem() instanceof MP4Item) || !isNetMusicDisc(discStack)) {
            return false;
        }
        List<ItemStack> queue = readQueue(mp4Stack);
        if (queue.size() >= MAX_QUEUE_SIZE) {
            return false;
        }
        queue.add(BiliSongInfoSanitizer.sanitizeDisc(discStack));
        writeQueue(mp4Stack, queue);
        discStack.shrink(1);
        return true;
    }

    public static ItemStack removeSelectedOrLastDisc(ItemStack mp4Stack) {
        List<ItemStack> queue = readQueue(mp4Stack);
        if (queue.isEmpty()) {
            return ItemStack.EMPTY;
        }
        int index = queue.size() - 1;
        ItemStack removed = queue.remove(index).copyWithCount(1);
        writeQueue(mp4Stack, queue);
        return removed;
    }

    private static void writeQueue(ItemStack stack, List<ItemStack> queue) {
        ListTag listTag = new ListTag();
        for (ItemStack disc : queue) {
            if (!isNetMusicDisc(disc)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store(DATA_QUEUE_STACK, ItemStack.OPTIONAL_CODEC, BiliSongInfoSanitizer.sanitizeDisc(disc));
            listTag.add(entry);
        }
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                existing -> existing.update(tag -> {
                    tag.put(DATA_QUEUE, listTag);
                }));
    }

    private static void syncQueueCopy(Player player, ItemStack mp4Stack) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(serverPlayer.level() instanceof ServerLevel level)
                || !(mp4Stack.getItem() instanceof MP4Item)) {
            return;
        }
        UUID deviceId = MP4DeviceIdentity.getOrCreateUnique(level, serverPlayer, mp4Stack);
        if (deviceId == null) {
            return;
        }
        MP4DeviceStateStore.syncQueueCopy(level, deviceId, mp4Stack);
    }

    public record State(boolean playing, boolean shuffle, boolean videoEnabled, boolean landscape, int qualityIndex,
            int selectedQueueIndex, int queueScrollOffset, int volumePerMille, int repeatMode, boolean playlistOpen,
            boolean lyricsEnabled, int subtitleMode, boolean subtitleAiEnabled, int progressPerMille,
            boolean rotationHintShown) {
        public static final int MAX_QUALITY_INDEX = 7;
        public static final int MAX_QUEUE_INDEX = 17;
        public static final int MAX_QUEUE_SCROLL_OFFSET = 15;
        public static final State DEFAULT = new State(false, false, true, false, 5, 0, 0, 1000, 0, false, false, 0,
                false, 0, false);

        public boolean videoDecodeEnabled() {
            return playing && videoEnabled && landscape;
        }

        public int videoQualityCeiling() {
            return switch (qualityIndex) {
                case 0 -> 127; // 8K
                case 1 -> 120; // 4K
                case 2 -> 116; // 1080P60
                case 3 -> 112; // 1080P+
                case 4 -> 80; // 1080P
                case 5 -> 64; // 720P
                case 6 -> 32; // 480P
                default -> 16; // 360P
            };
        }
    }
}
