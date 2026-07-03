package com.zhongbai233.net_music_can_play_bili.item.pad;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nonnull;
import java.util.Objects;

public record PadMediaEntry(int mediaId, @Nonnull ItemStack disc) {
    public PadMediaEntry {
        mediaId = Math.max(1, mediaId);
        disc = sanitizeDisc(disc);
    }

    @Nonnull
    private static ItemStack sanitizeDisc(ItemStack disc) {
        if (disc == null || disc.isEmpty()) {
            return emptyStack();
        }
        return Objects.requireNonNull(disc.copyWithCount(1), "copyWithCount");
    }

    @Nonnull
    private static ItemStack emptyStack() {
        return Objects.requireNonNull(ItemStack.EMPTY, "ItemStack.EMPTY");
    }
}