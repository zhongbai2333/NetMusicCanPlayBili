package com.zhongbai233.net_music_can_play_bili.menu;

import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.link.HeadphoneAbility;
import com.zhongbai233.net_music_can_play_bili.link.HolographicGlassesAbility;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceIdentity;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class MediaToolBindingMenu extends AbstractContainerMenu {
    public static final int TARGET_SLOT = 0;
    public static final int INPUT_SLOT = 1;
    public static final int OUTPUT_SLOT = 2;

    private final SimpleContainer toolContainer = new SimpleContainer(3);
    private final TargetKind targetKind;
    private final BlockPos targetPos;
    private final UUID mp4DeviceId;
    private int headphoneBindingCount;
    private int holographicBindingCount;
    private boolean confirmed;
    private final Player owner;

    public enum TargetKind {
        TURNTABLE,
        MP4
    }

    public MediaToolBindingMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, TargetKind.MP4, null, null);
    }

    public MediaToolBindingMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, inventory, readTargetKind(buffer), readTargetPos(buffer), readMp4DeviceId(buffer));
    }

    public MediaToolBindingMenu(int containerId, Inventory inventory, TargetKind targetKind, BlockPos targetPos,
            UUID mp4DeviceId) {
        super(ModMenus.MEDIA_TOOL_BINDING.get(), containerId);
        this.targetKind = targetKind != null ? targetKind : TargetKind.MP4;
        this.targetPos = targetPos != null ? targetPos.immutable() : null;
        this.mp4DeviceId = mp4DeviceId;
        this.owner = inventory.player;
        if (!usesManualMp4TargetSlot()) {
            toolContainer.setItem(TARGET_SLOT, targetIcon());
        }

        addSlot(usesManualMp4TargetSlot()
                ? new Mp4TargetSlot(toolContainer, TARGET_SLOT, 43, 43, this)
                : new LockedSlot(toolContainer, TARGET_SLOT, 43, 43));
        addSlot(new EquipmentInputSlot(toolContainer, INPUT_SLOT, 155, 43));
        addSlot(new OutputSlot(toolContainer, OUTPUT_SLOT, 155, 85));

        addPlayerInventory(inventory, 22, 120);
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return headphoneBindingCount;
            }

            @Override
            public void set(int value) {
                headphoneBindingCount = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return holographicBindingCount;
            }

            @Override
            public void set(int value) {
                holographicBindingCount = value;
            }
        });
        refreshTargetBindingStats(inventory.player);
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    public BlockPos targetPos() {
        return targetPos;
    }

    public UUID mp4DeviceId() {
        return mp4DeviceId;
    }

    public boolean usesManualMp4TargetSlot() {
        return targetKind == TargetKind.MP4 && mp4DeviceId == null;
    }

    public static void writeClientData(RegistryFriendlyByteBuf buffer, TargetKind targetKind, BlockPos targetPos,
            UUID mp4DeviceId) {
        TargetKind safeKind = targetKind != null ? targetKind : TargetKind.MP4;
        buffer.writeEnum(safeKind);
        buffer.writeBoolean(targetPos != null);
        if (targetPos != null) {
            buffer.writeBlockPos(targetPos);
        }
        buffer.writeBoolean(mp4DeviceId != null);
        if (mp4DeviceId != null) {
            buffer.writeUUID(mp4DeviceId);
        }
    }

    public ItemStack manualMp4TargetStack() {
        return usesManualMp4TargetSlot() ? toolContainer.getItem(TARGET_SLOT) : ItemStack.EMPTY;
    }

    public int headphoneBindingCount() {
        return headphoneBindingCount;
    }

    public int holographicBindingCount() {
        return holographicBindingCount;
    }

    public int totalTargetBindingCount() {
        return headphoneBindingCount + holographicBindingCount;
    }

    public MediaSource targetSource(Player player) {
        return switch (targetKind) {
            case MP4 -> {
                UUID deviceId = mp4DeviceId;
                if (deviceId == null) {
                    ItemStack mp4Stack = manualMp4TargetStack();
                    deviceId = mp4Stack.getItem() instanceof MP4Item
                        && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                            ? MP4DeviceIdentity.getOrCreateUnique(
                                (net.minecraft.server.level.ServerLevel) serverPlayer.level(), serverPlayer, mp4Stack)
                            : null;
                }
                yield MediaBindingCleanupService.mp4Source(deviceId);
            }
            case TURNTABLE -> MediaBindingCleanupService.turntableSource(
                    player != null ? player.level() : null, targetPos);
        };
    }

    public void refreshTargetBindingStats(Player player) {
        var stats = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? MediaBindingCleanupService.countTargetBindings(serverPlayer, targetSource(player))
                : MediaBindingCleanupService.TargetBindingStats.EMPTY;
        headphoneBindingCount = stats.headphoneCount();
        holographicBindingCount = stats.holographicCount();
        broadcastChanges();
    }

    public boolean hasInputEquipment() {
        return !toolContainer.getItem(INPUT_SLOT).isEmpty();
    }

    public boolean confirmBinding(Player player) {
        if (confirmed) {
            return false;
        }
        ItemStack input = toolContainer.getItem(INPUT_SLOT);
        if (input.isEmpty()) {
            return false;
        }
        if (!toolContainer.getItem(OUTPUT_SLOT).isEmpty()) {
            return false;
        }
        toolContainer.setItem(INPUT_SLOT, ItemStack.EMPTY);
        toolContainer.setItem(OUTPUT_SLOT, input);
        confirmed = true;
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();
        if (index == OUTPUT_SLOT) {
            if (!moveItemStackTo(original, 3, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= 3) {
            int destinationSlot = usesManualMp4TargetSlot() && original.getItem() instanceof MP4Item
                    ? TARGET_SLOT
                    : INPUT_SLOT;
            if (!moveItemStackTo(original, destinationSlot, destinationSlot + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index == INPUT_SLOT) {
            if (!moveItemStackTo(original, 3, slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (index == TARGET_SLOT && usesManualMp4TargetSlot()) {
            if (!moveItemStackTo(original, 3, slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }
        if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (container == toolContainer) {
            confirmed = false;
            refreshTargetBindingStats(owner);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide()) {
            if (usesManualMp4TargetSlot()) {
                returnRealSlotToPlayer(player, TARGET_SLOT);
            }
            returnRealSlotToPlayer(player, INPUT_SLOT);
            returnRealSlotToPlayer(player, OUTPUT_SLOT);
            toolContainer.setItem(TARGET_SLOT, ItemStack.EMPTY);
        }
    }

    private void returnRealSlotToPlayer(Player player, int slot) {
        ItemStack stack = toolContainer.removeItemNoUpdate(slot);
        if (!stack.isEmpty()) {
            player.getInventory().placeItemBackInInventory(stack);
        }
    }

    private void addPlayerInventory(Inventory inventory, int left, int top) {
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                addSlot(new Slot(inventory, col + row * 9 + 9, left + col * 18, top + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            addSlot(new Slot(inventory, col, left + col * 18, top + 58));
        }
    }

    private ItemStack targetIcon() {
        return switch (targetKind) {
            case TURNTABLE -> new ItemStack(ModItems.MODERN_TURNTABLE.get());
            case MP4 -> new ItemStack(ModItems.MP4.get());
        };
    }

    private static TargetKind readTargetKind(RegistryFriendlyByteBuf buffer) {
        return buffer != null && buffer.isReadable()
                ? buffer.readEnum(TargetKind.class)
                : TargetKind.MP4;
    }

    private static BlockPos readTargetPos(RegistryFriendlyByteBuf buffer) {
        return buffer != null && buffer.isReadable() && buffer.readBoolean()
                ? buffer.readBlockPos()
                : null;
    }

    private static UUID readMp4DeviceId(RegistryFriendlyByteBuf buffer) {
        return buffer != null && buffer.isReadable() && buffer.readBoolean()
                ? buffer.readUUID()
                : null;
    }

    private static final class LockedSlot extends Slot {
        private LockedSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    private static final class Mp4TargetSlot extends Slot {
        private final MediaToolBindingMenu menu;

        private Mp4TargetSlot(Container container, int slot, int x, int y, MediaToolBindingMenu menu) {
            super(container, slot, x, y);
            this.menu = menu;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof MP4Item;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            if (!menu.owner.level().isClientSide()) {
                menu.confirmed = false;
                menu.refreshTargetBindingStats(menu.owner);
            }
        }
    }

    private static final class EquipmentInputSlot extends Slot {
        private EquipmentInputSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return HeadphoneAbility.has(stack) || HolographicGlassesAbility.has(stack);
        }
    }

    private static final class OutputSlot extends Slot {
        private OutputSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
