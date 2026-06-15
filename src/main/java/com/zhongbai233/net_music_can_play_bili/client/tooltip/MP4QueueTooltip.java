package com.zhongbai233.net_music_can_play_bili.client.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

import java.util.List;

public record MP4QueueTooltip(List<String> titles, int selectedIndex) implements TooltipComponent {
    public MP4QueueTooltip {
        titles = List.copyOf(titles);
    }
}
