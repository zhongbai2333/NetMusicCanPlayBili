package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStackResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One-slot automation adapter for a modern turntable's music disc. */
final class ModernTurntableDiscHandler extends ItemStackResourceHandler {
    private final Supplier<ItemStack> stackGetter;
    private final Consumer<ItemStack> stackSetter;
    private final BooleanSupplier extractionAllowed;
    private final Consumer<ItemStack> commitListener;

    ModernTurntableDiscHandler(Supplier<ItemStack> stackGetter, Consumer<ItemStack> stackSetter,
            BooleanSupplier extractionAllowed, Consumer<ItemStack> commitListener) {
        this.stackGetter = stackGetter;
        this.stackSetter = stackSetter;
        this.extractionAllowed = extractionAllowed;
        this.commitListener = commitListener;
    }

    @Override
    protected ItemStack getStack() {
        return stackGetter.get();
    }

    @Override
    protected void setStack(ItemStack stack) {
        stackSetter.accept(stack);
    }

    @Override
    protected boolean isValid(ItemResource resource) {
        ItemStack stack = Objects.requireNonNull(resource.toStack(), "ItemResource.toStack");
        return ItemMusicCD.getSongInfo(stack) != null;
    }

    @Override
    protected int getCapacity(ItemResource resource) {
        return 1;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        return extractionAllowed.getAsBoolean() ? super.extract(index, resource, amount, transaction) : 0;
    }

    @Override
    protected void onRootCommit(ItemStack originalStack) {
        commitListener.accept(originalStack);
    }
}
